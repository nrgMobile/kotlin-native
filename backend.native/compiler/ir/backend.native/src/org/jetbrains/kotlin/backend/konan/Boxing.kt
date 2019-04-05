/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import llvm.*
import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedValueParameterDescriptor
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.KotlinType

internal fun KonanSymbols.getTypeConversion(actualType: IrType, expectedType: IrType): IrSimpleFunctionSymbol? =
        getTypeConversionImpl(actualType.getInlinedClassNative(), expectedType.getInlinedClassNative())

internal fun KonanSymbols.getTypeConversion(actualType: KotlinType, expectedType: KotlinType): IrSimpleFunctionSymbol? {
    // TODO: rework all usages and remove this method.
    val actualInlinedClass = actualType.getInlinedClass()?.let { context.ir.get(it) }
    val expectedInlinedClass = expectedType.getInlinedClass()?.let { context.ir.get(it) }

    return getTypeConversionImpl(actualInlinedClass, expectedInlinedClass)
}

private fun KonanSymbols.getTypeConversionImpl(
        actualInlinedClass: IrClass?,
        expectedInlinedClass: IrClass?
): IrSimpleFunctionSymbol? {
    if (actualInlinedClass == expectedInlinedClass) return null

    return when {
        actualInlinedClass == null && expectedInlinedClass == null -> null
        actualInlinedClass != null && expectedInlinedClass == null -> context.getBoxFunction(actualInlinedClass)
        actualInlinedClass == null && expectedInlinedClass != null -> context.getUnboxFunction(expectedInlinedClass)
        else -> error("actual type is ${actualInlinedClass?.fqNameSafe}, expected ${expectedInlinedClass?.fqNameSafe}")
    }?.symbol
}

internal val Context.getBoxFunction: (IrClass) -> IrSimpleFunction by Context.lazyMapMember { inlinedClass ->
    assert(inlinedClass.isUsedAsBoxClass())

    val symbols = ir.symbols

    val isNullable = inlinedClass.inlinedClassIsNullable()
    val unboxedType = inlinedClass.defaultOrNullableType(isNullable)
    val boxedType = symbols.any.owner.defaultOrNullableType(isNullable)

    val parameterType = unboxedType
    val returnType = boxedType

    val startOffset = inlinedClass.startOffset
    val endOffset = inlinedClass.endOffset

    val descriptor = WrappedSimpleFunctionDescriptor()
    IrFunctionImpl(
            startOffset, endOffset,
            IrDeclarationOrigin.DEFINED,
            IrSimpleFunctionSymbolImpl(descriptor),
            Name.special("<box>"),
            Visibilities.PUBLIC,
            Modality.FINAL,
            returnType,
            isInline = false,
            isExternal = false,
            isTailrec = false,
            isSuspend = false
    ).also { function ->
        function.valueParameters.add(WrappedValueParameterDescriptor().let {
            IrValueParameterImpl(
                    startOffset, endOffset,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(it),
                    Name.identifier("value"),
                    index = 0,
                    varargElementType = null,
                    isCrossinline = false,
                    type = parameterType,
                    isNoinline = false
            ).apply {
                it.bind(this)
                parent = function
            }
        })
        descriptor.bind(function)
        function.parent = inlinedClass
    }
}

internal val Context.getUnboxFunction: (IrClass) -> IrSimpleFunction by Context.lazyMapMember { inlinedClass ->
    assert(inlinedClass.isUsedAsBoxClass())

    val symbols = ir.symbols

    val isNullable = inlinedClass.inlinedClassIsNullable()
    val unboxedType = inlinedClass.defaultOrNullableType(isNullable)
    val boxedType = symbols.any.owner.defaultOrNullableType(isNullable)

    val parameterType = boxedType
    val returnType = unboxedType

    val startOffset = inlinedClass.startOffset
    val endOffset = inlinedClass.endOffset

    val descriptor = WrappedSimpleFunctionDescriptor()
    IrFunctionImpl(
            startOffset, endOffset,
            IrDeclarationOrigin.DEFINED,
            IrSimpleFunctionSymbolImpl(descriptor),
            Name.special("<unbox>"),
            Visibilities.PUBLIC,
            Modality.FINAL,
            returnType,
            isInline = false,
            isExternal = false,
            isTailrec = false,
            isSuspend = false
    ).also { function ->
        function.valueParameters.add(WrappedValueParameterDescriptor().let {
            IrValueParameterImpl(
                    startOffset, endOffset,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(it),
                    Name.identifier("value"),
                    index = 0,
                    varargElementType = null,
                    isCrossinline = false,
                    type = parameterType,
                    isNoinline = false
            ).apply {
                it.bind(this)
                parent = function
            }
        })
        descriptor.bind(function)
        function.parent = inlinedClass
    }
}

/**
 * Initialize static boxing.
 * If output target is native binary then the cache is created.
 */
internal fun initializeCachedBoxes(context: Context) {
    if (context.config.produce.isNativeBinary) {
        BoxCache.values().forEach { cache ->
            val cacheName = "${cache.name}_CACHE"
            val rangeStart = "${cache.name}_RANGE_FROM"
            val rangeEnd = "${cache.name}_RANGE_TO"
            initCache(cache, context, cacheName, rangeStart, rangeEnd)
        }
    }
}

/**
 * Adds global that refers to the cache.
 */
private fun initCache(cache: BoxCache, context: Context, cacheName: String,
                      rangeStartName: String, rangeEndName: String) {

    val kotlinType = context.irBuiltIns.getKotlinClass(cache)
    val staticData = context.llvm.staticData
    val llvmType = staticData.getLLVMType(kotlinType.defaultType)

    val (start, end) = context.config.target.getBoxCacheRange(cache)
    // Constancy of these globals allows LLVM's constant propagation and DCE
    // to remove fast path of boxing function in case of empty range.
    staticData.placeGlobal(rangeStartName, createConstant(llvmType, start), true)
            .setConstant(true)
    staticData.placeGlobal(rangeEndName, createConstant(llvmType, end), true)
            .setConstant(true)
    val values = (start..end).map { staticData.createInitializer(kotlinType, createConstant(llvmType, it)) }
    val llvmBoxType = structType(context.llvm.runtime.objHeaderType, llvmType)
    staticData.placeGlobalConstArray(cacheName, llvmBoxType, values, true).llvm
}

private fun createConstant(llvmType: LLVMTypeRef, value: Int): ConstValue =
        constValue(LLVMConstInt(llvmType, value.toLong(), 1)!!)

// When start is greater than end then `inRange` check is always false
// and can be eliminated by LLVM.
private val emptyRange = 1 to 0

// Memory usage is around 20kb.
private val BoxCache.defaultRange get() = when (this) {
    BoxCache.BOOLEAN -> (0 to 1)
    BoxCache.BYTE -> (-128 to 127)
    BoxCache.SHORT -> (-128 to 127)
    BoxCache.CHAR -> (0 to 255)
    BoxCache.INT -> (-128 to 127)
    BoxCache.LONG -> (-128 to 127)
}

private fun KonanTarget.getBoxCacheRange(cache: BoxCache): Pair<Int, Int> = when (this) {
    is KonanTarget.ZEPHYR   -> emptyRange
    else                    -> cache.defaultRange
}

internal fun IrBuiltIns.getKotlinClass(cache: BoxCache): IrClass = when (cache) {
    BoxCache.BOOLEAN -> booleanClass
    BoxCache.BYTE -> byteClass
    BoxCache.SHORT -> shortClass
    BoxCache.CHAR -> charClass
    BoxCache.INT -> intClass
    BoxCache.LONG -> longClass
}.owner

// TODO: consider adding box caches for unsigned types.
enum class BoxCache {
    BOOLEAN, BYTE, SHORT, CHAR, INT, LONG
}
