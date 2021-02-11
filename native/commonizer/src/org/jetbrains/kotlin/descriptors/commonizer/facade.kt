/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer

import kotlinx.metadata.klib.ChunkedKlibModuleFragmentWriteStrategy
import org.jetbrains.kotlin.descriptors.commonizer.ResultsConsumer.ModuleResult
import org.jetbrains.kotlin.descriptors.commonizer.ResultsConsumer.Status
import org.jetbrains.kotlin.descriptors.commonizer.core.CommonizationVisitor
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.*
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirNode.Companion.dimension
import org.jetbrains.kotlin.descriptors.commonizer.mergedtree.CirTreeMerger.CirTreeMergeResult
import org.jetbrains.kotlin.descriptors.commonizer.metadata.CirTreeMergerV2
import org.jetbrains.kotlin.descriptors.commonizer.metadata.MetadataBuilder
import org.jetbrains.kotlin.descriptors.commonizer.utils.isNull
import org.jetbrains.kotlin.library.SerializedMetadata
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager

fun runCommonization(parameters: CommonizerParameters) {
    if (!parameters.hasAnythingToCommonize()) {
        parameters.resultsConsumer.allConsumed(Status.NOTHING_TO_DO)
        return
    }

    val storageManager = LockBasedStorageManager("Declarations commonization")

    val mergeResult = mergeAndCommonize(storageManager, parameters)
    val mergedTree = mergeResult.root

    // build resulting declarations:
    for (targetIndex in 0 until mergedTree.dimension) {
        serializeTarget(mergeResult, targetIndex, parameters)
    }

    parameters.resultsConsumer.allConsumed(Status.DONE)
}

private fun mergeAndCommonize(storageManager: StorageManager, parameters: CommonizerParameters): CirTreeMergeResult {
    // build merged tree:
    val classifiers = CirKnownClassifiers(
        commonized = CirCommonizedClassifiers.default(),
        forwardDeclarations = CirForwardDeclarations.default(),
        dependeeLibraries = mapOf(
            // for now, supply only common dependee libraries (ex: Kotlin stdlib)
            parameters.sharedTarget to CirProvidedClassifiers.fromModules(storageManager) {
                parameters.dependeeModulesProvider?.loadModules(emptyList())?.values.orEmpty()
            }
        )
    )
    val mergeResult = CirTreeMerger(storageManager, classifiers, parameters).merge()

    val classifiersV2 = CirKnownClassifiers(
        commonized = CirCommonizedClassifiers.default(),
        forwardDeclarations = CirForwardDeclarations.default(),
        dependeeLibraries = mapOf(
            // for now, supply only common dependee libraries (ex: Kotlin stdlib)
            parameters.sharedTarget to CirProvidedClassifiers.fromModules(storageManager) {
                parameters.dependeeModulesProvider?.loadModules(emptyList())?.values.orEmpty()
            }
        )
    )
    val mergeResultV2 = CirTreeMergerV2(storageManager, classifiersV2, parameters).merge()
    diffTrees(mergeResult.root, mergeResultV2.root)

    // commonize:
    val mergedTree = mergeResult.root
    mergedTree.accept(CommonizationVisitor(classifiers, mergedTree), Unit)
    parameters.progressLogger?.invoke("Commonized declarations")

    return mergeResult
}

private fun serializeTarget(mergeResult: CirTreeMergeResult, targetIndex: Int, parameters: CommonizerParameters) {
    val mergedTree = mergeResult.root
    val target = mergedTree.getTarget(targetIndex)

    MetadataBuilder.build(mergedTree, targetIndex, parameters.statsCollector) { metadataModule ->
        val libraryName = metadataModule.name
        val serializedMetadata = with(metadataModule.write(KLIB_FRAGMENT_WRITE_STRATEGY)) {
            SerializedMetadata(header, fragments, fragmentNames)
        }

        parameters.resultsConsumer.consume(target, ModuleResult.Commonized(libraryName, serializedMetadata))
    }

    if (target is LeafTarget) {
        mergeResult.missingModuleInfos.getValue(target).forEach {
            parameters.resultsConsumer.consume(target, ModuleResult.Missing(it.originalLocation))
        }
    }

    parameters.resultsConsumer.targetConsumed(target)
}

@Suppress("DuplicatedCode")
private fun diffTrees(oldRootNode: CirRootNode, newRootNode: CirRootNode) {
    fun <T> checkKeys(where: String, oldKeys: Set<T>, newKeys: Set<T>): Collection<T> {
        val common = ArrayList<T>(oldKeys.size + newKeys.size)
        val missingInNew = ArrayList<T>(newKeys.size)
        val missingInOld = ArrayList<T>(oldKeys.size)

        oldKeys.forEach { oldKey ->
            if (oldKey in newKeys)
                common += oldKey
            else
                missingInNew += oldKey
        }

        newKeys.forEach { newKey ->
            if (newKey !in oldKeys)
                missingInOld += newKey
        }

        if (missingInNew.isNotEmpty())
            println("NEW(${missingInNew.size}) $where: " + missingInNew.sortedBy { it.toString() })

        if (missingInOld.isNotEmpty())
            println("OLD(${missingInOld.size}) $where: " + missingInOld.sortedBy { it.toString() })

        if (missingInNew.size != missingInOld.size)
            print("")

        return common
    }

    fun checkMembers(where: String, oldNode: CirNodeWithMembers<*, *>, newNode: CirNodeWithMembers<*, *>) {
        checkKeys(
            "property in $where",
            oldNode.properties.keys,
            newNode.properties.keys
        ).forEach { propertyKey ->
            val oldPropertyNode = oldNode.properties.getValue(propertyKey)
            val newPropertyNode = newNode.properties.getValue(propertyKey)

            for (i in 0 until oldPropertyNode.targetDeclarations.size) {
                val oldProperty = oldPropertyNode.targetDeclarations[i]
                val newProperty = newPropertyNode.targetDeclarations[i]

                check(oldProperty.isNull() == newProperty.isNull())
            }
        }

        checkKeys(
            "function in $where",
            oldNode.functions.keys,
            newNode.functions.keys
        ).forEach { propertyKey ->
            val oldFunctionNode = oldNode.functions.getValue(propertyKey)
            val newFunctionNode = newNode.functions.getValue(propertyKey)

            for (i in 0 until oldFunctionNode.targetDeclarations.size) {
                val oldFunction = oldFunctionNode.targetDeclarations[i]
                val newFunction = newFunctionNode.targetDeclarations[i]

                check(oldFunction.isNull() == newFunction.isNull())
            }
        }

        if (oldNode is CirClassNode && newNode is CirClassNode) {
            checkKeys(
                "constructor in $where",
                oldNode.constructors.keys,
                newNode.constructors.keys
            ).forEach { functionKey ->
                val oldConstructorNode = oldNode.constructors.getValue(functionKey)
                val newConstructorNode = newNode.constructors.getValue(functionKey)

                for (i in 0 until oldConstructorNode.targetDeclarations.size) {
                    val oldConstructor = oldConstructorNode.targetDeclarations[i]
                    val newConstructor = newConstructorNode.targetDeclarations[i]

                    check(oldConstructor.isNull() == newConstructor.isNull())
                }
            }
        }

        checkKeys(
            "class in $where",
            oldNode.classes.keys,
            newNode.classes.keys
        ).forEach { className ->
            val oldClassNode = oldNode.classes.getValue(className)
            val newClassNode = newNode.classes.getValue(className)

            for (i in 0 until oldClassNode.targetDeclarations.size) {
                val oldClass = oldClassNode.targetDeclarations[i]
                val newClass = newClassNode.targetDeclarations[i]

                check(oldClass.isNull() == newClass.isNull())
            }

            checkMembers("$where > $className", oldClassNode, newClassNode)
        }
    }

    checkKeys(
        "module name",
        oldRootNode.modules.keys,
        newRootNode.modules.keys
    ).forEach { moduleName ->
        val oldModuleNode = oldRootNode.modules.getValue(moduleName)
        val newModuleNode = newRootNode.modules.getValue(moduleName)

        val shortModuleName = moduleName.name.replace("org.jetbrains.kotlin.native.platform.", "")

        checkKeys(
            "package in $shortModuleName",
            oldModuleNode.packages.keys,
            newModuleNode.packages.keys
        ).forEach { packageName ->
            val oldPackageNode = oldModuleNode.packages.getValue(packageName)
            val newPackageNode = newModuleNode.packages.getValue(packageName)

            checkMembers("$shortModuleName > $packageName", oldPackageNode, newPackageNode)

            checkKeys(
                "TA in $shortModuleName > $packageName",
                oldPackageNode.typeAliases.keys,
                newPackageNode.typeAliases.keys
            ).forEach { typeAliasName ->
                val oldTypeAliasNode = oldPackageNode.typeAliases.getValue(typeAliasName)
                val newTypeAliasNode = newPackageNode.typeAliases.getValue(typeAliasName)

                for (i in 0 until oldTypeAliasNode.targetDeclarations.size) {
                    val oldTypeAlias = oldTypeAliasNode.targetDeclarations[i]
                    val newTypeAlias = newTypeAliasNode.targetDeclarations[i]

                    check(oldTypeAlias.isNull() == newTypeAlias.isNull())
                }
            }
        }
    }
}

private val KLIB_FRAGMENT_WRITE_STRATEGY = ChunkedKlibModuleFragmentWriteStrategy()
