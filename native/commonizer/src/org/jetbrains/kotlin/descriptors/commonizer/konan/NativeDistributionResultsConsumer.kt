/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors.commonizer.konan

import com.intellij.util.containers.FactoryMap
import org.jetbrains.kotlin.descriptors.commonizer.*
import org.jetbrains.kotlin.descriptors.commonizer.ResultsConsumer.ModuleResult
import org.jetbrains.kotlin.descriptors.commonizer.ResultsConsumer.Status
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_COMMON_LIBS_DIR
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_KLIB_DIR
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_PLATFORM_LIBS_DIR
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.library.SerializedMetadata
import org.jetbrains.kotlin.library.impl.BaseWriterImpl
import org.jetbrains.kotlin.library.impl.BuiltInsPlatform
import org.jetbrains.kotlin.library.impl.KotlinLibraryLayoutForWriter
import org.jetbrains.kotlin.library.impl.KotlinLibraryWriterImpl
import java.io.File
import org.jetbrains.kotlin.konan.file.File as KFile

internal class NativeDistributionResultsConsumer(
    private val repository: File,
    private val originalLibraries: AllNativeLibraries,
    private val destination: File,
    private val copyStdlib: Boolean,
    private val copyEndorsedLibs: Boolean,
    private val logProgress: (String) -> Unit
) : ResultsConsumer {
    private val allLeafTargets = originalLibraries.librariesByTargets.keys

    private val allLeafCommonizedTargets = originalLibraries.librariesByTargets.filterValues { it.libraries.isNotEmpty() }.keys
    private val sharedTarget = SharedCommonizerTarget(allLeafCommonizedTargets)

    private val consumedTargets = LinkedHashSet<CommonizerTarget>()

    private val cachedManifestProviders = FactoryMap.create<CommonizerTarget, NativeManifestDataProvider> { target ->
        when (target) {
            is LeafCommonizerTarget -> originalLibraries.librariesByTargets.getValue(target)
            is SharedCommonizerTarget -> CommonNativeManifestDataProvider(originalLibraries.librariesByTargets.values)
        }
    }

    private val cachedLibrariesDestination = FactoryMap.create<CommonizerTarget, File> { target ->
        val librariesDestination = when (target) {
            is LeafCommonizerTarget -> destination.resolve(KONAN_DISTRIBUTION_PLATFORM_LIBS_DIR).resolve(target.name)
            is SharedCommonizerTarget -> destination.resolve(KONAN_DISTRIBUTION_COMMON_LIBS_DIR)
        }

        librariesDestination.mkdirs() // always create an empty directory even if there is nothing to copy
        librariesDestination
    }

    override fun consume(target: CommonizerTarget, moduleResult: ModuleResult) {
        check(target in allLeafCommonizedTargets || target == sharedTarget)
        consumedTargets += target

        serializeModule(target, moduleResult)
    }

    override fun targetConsumed(target: CommonizerTarget) {
        check(target in consumedTargets)

        logProgress("Written libraries for ${sharedTarget.contextualIdentityString(target)}")
    }

    override fun allConsumed(status: Status) {
        // optimization: stdlib and endorsed libraries effectively remain the same across all Kotlin/Native targets,
        // so they can be just copied to the new destination without running serializer
        copyCommonStandardLibraries()

        when (status) {
            Status.NOTHING_TO_DO -> {
                // It may happen that all targets to be commonized (or at least all but one target) miss platform libraries.
                // In such case commonizer will do nothing and raise a special status value 'NOTHING_TO_DO'.
                // So, let's just copy platform libraries from the target where they are to the new destination.
                allLeafTargets.forEach(::copyTargetAsIs)
            }
            Status.DONE -> {
                // 'targetsToCopy' are some leaf targets with empty set of platform libraries
                val targetsToCopy = allLeafTargets - consumedTargets.filterIsInstance<LeafCommonizerTarget>()
                targetsToCopy.forEach(::copyTargetAsIs)
            }
        }
    }

    private fun copyCommonStandardLibraries() {
        if (copyStdlib || copyEndorsedLibs) {
            repository.resolve(KONAN_DISTRIBUTION_KLIB_DIR)
                .resolve(KONAN_DISTRIBUTION_COMMON_LIBS_DIR)
                .listFiles()
                ?.filter { it.isDirectory }
                ?.let {
                    if (copyStdlib) {
                        if (copyEndorsedLibs) it else it.filter { dir -> dir.endsWith(KONAN_STDLIB_NAME) }
                    } else
                        it.filter { dir -> !dir.endsWith(KONAN_STDLIB_NAME) }
                }?.forEach { libraryOrigin ->
                    val libraryDestination = destination.resolve(KONAN_DISTRIBUTION_COMMON_LIBS_DIR).resolve(libraryOrigin.name)
                    libraryOrigin.copyRecursively(libraryDestination)
                }

            val what = listOfNotNull(
                "standard library".takeIf { copyStdlib },
                "endorsed libraries".takeIf { copyEndorsedLibs }
            ).joinToString(separator = " and ")

            logProgress("Copied $what")
        }
    }

    private fun copyTargetAsIs(leafTarget: LeafCommonizerTarget) {
        val librariesCount = originalLibraries.librariesByTargets.getValue(leafTarget).libraries.size
        val librariesDestination = cachedLibrariesDestination.getValue(leafTarget)

        val librariesSource = leafTarget.platformLibrariesSource
        if (librariesSource.isDirectory) librariesSource.copyRecursively(librariesDestination)

        logProgress("Copied $librariesCount libraries for ${leafTarget.identityString}")
    }

    private fun serializeModule(target: CommonizerTarget, moduleResult: ModuleResult) {
        val librariesDestination = cachedLibrariesDestination.getValue(target)

        when (moduleResult) {
            is ModuleResult.Commonized -> {
                val libraryName = moduleResult.libraryName

                val manifestData = cachedManifestProviders.getValue(target).getManifest(libraryName)
                val libraryDestination = librariesDestination.resolve(libraryName)

                writeLibrary(moduleResult.metadata, manifestData, libraryDestination)
            }
            is ModuleResult.Missing -> {
                val libraryName = moduleResult.libraryName
                val missingModuleLocation = moduleResult.originalLocation

                missingModuleLocation.copyRecursively(librariesDestination.resolve(libraryName))
            }
        }
    }

    private fun writeLibrary(
        metadata: SerializedMetadata,
        manifestData: NativeSensitiveManifestData,
        libraryDestination: File
    ) {
        val layout = KFile(libraryDestination.path).let { KotlinLibraryLayoutForWriter(it, it) }
        val library = KotlinLibraryWriterImpl(
            moduleName = manifestData.uniqueName,
            versions = manifestData.versions,
            builtInsPlatform = BuiltInsPlatform.NATIVE,
            nativeTargets = emptyList(), // will be overwritten with NativeSensitiveManifestData.applyTo() below
            nopack = true,
            shortName = manifestData.shortName,
            layout = layout
        )
        library.addMetadata(metadata)
        manifestData.applyTo(library.base as BaseWriterImpl)
        library.commit()
    }

    private val LeafCommonizerTarget.platformLibrariesSource: File
        get() = repository.resolve(KONAN_DISTRIBUTION_KLIB_DIR)
            .resolve(KONAN_DISTRIBUTION_PLATFORM_LIBS_DIR)
            .resolve(name)
}
