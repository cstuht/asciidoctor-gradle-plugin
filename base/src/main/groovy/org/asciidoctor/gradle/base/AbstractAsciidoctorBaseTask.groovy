/*
 * Copyright 2013-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asciidoctor.gradle.base

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.asciidoctor.gradle.base.internal.DefaultAsciidoctorBaseDirConfiguration
import org.asciidoctor.gradle.base.internal.Workspace
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileTreeElement
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.tasks.util.PatternSet
import org.ysb33r.grolifant.api.core.ProjectOperations

import java.nio.file.Path

import static org.asciidoctor.gradle.base.AsciidoctorUtils.UNDERSCORE_LED_FILES
import static org.asciidoctor.gradle.base.AsciidoctorUtils.createDirectoryProperty
import static org.asciidoctor.gradle.base.AsciidoctorUtils.executeDelegatingClosure
import static org.asciidoctor.gradle.base.AsciidoctorUtils.mapToDirectoryProvider
import static org.gradle.api.tasks.PathSensitivity.RELATIVE
import static org.ysb33r.grolifant.api.core.TaskInputFileOptions.IGNORE_EMPTY_DIRECTORIES
import static org.ysb33r.grolifant.api.core.TaskInputFileOptions.SKIP_WHEN_EMPTY

/**
 * Abstract base task for Asciidoctor that can be shared between AsciidoctorJ and Asciidoctor.js.
 *
 * @author Schalk W. Cronjé
 * @author Lari Hotari
 * @author Gary Hale
 *
 * @since 3.0
 */
@CompileStatic
@SuppressWarnings(['MethodCount', 'ClassSize'])
abstract class AbstractAsciidoctorBaseTask extends DefaultTask implements AsciidoctorTaskMethods {

    @Delegate
    private final AsciidoctorTaskBaseDirConfiguration baseDirConfiguration

    private final DirectoryProperty srcDir
    private final DirectoryProperty outDir
    private final ProjectOperations projectOperations
    private PatternSet sourceDocumentPattern
    private PatternSet secondarySourceDocumentPattern
    private CopySpec resourceCopy
    private List<String> copyResourcesForBackends = []
    private boolean withIntermediateWorkDir = false
    private final List<String> languages = []
    private final Map<String, CopySpec> languageResources = [:]
    private final OutputOptions configuredOutputOptions = new OutputOptions()
    private final Provider<String> defaultRevNumber
    private final Provider<File> intermediateWorkDirProvider
    private final Property<PatternSet> intermediateArtifactPattern

    /** Logs documents as they are converted
     *
     */
    @Console
    boolean logDocuments = false

    /** Sets the new Asciidoctor parent source directory.
     *
     * @param f Any object convertible with {@code project.file}.
     */
    void setSourceDir(Object f) {
        this.srcDir.set(mapToDirectoryProvider(project, f))
    }

    /** Sets the new Asciidoctor parent source directory in a declarative style.
     *
     * @param f Any object convertible with {@code project.file}.
     *
     * @since 3.0
     */
    void sourceDir(Object f) {
        this.srcDir.set(mapToDirectoryProvider(project, f))
    }

    /** Returns the parent directory for Asciidoctor source.
     */
    @Internal
    File getSourceDir() {
        srcDir.asFile.get()
    }

    /**
     * Returns the parent directory for Asciidoctor source as a property object.
     */
    @Internal
    DirectoryProperty getSourceDirProperty() {
        this.srcDir
    }

    /** Returns the current toplevel output directory
     *
     */
    @OutputDirectory
    File getOutputDir() {
        this.outDir.asFile.get()
    }

    /** Sets the new Asciidoctor parent output directory.
     *
     * @param f An object convertible via {@code project.file}
     */
    void setOutputDir(Object f) {
        this.outDir.set(project.file(f))
    }

    /**
     * Returns the current toplevel output directory as a property object.
     */
    @Internal
    DirectoryProperty getOutputDirProperty() {
        this.outDir
    }

    /** Configures sources.
     *
     * @param cfg Configuration closure. Is passed a {@link PatternSet}.
     */
    void sources(final Closure cfg) {
        if (sourceDocumentPattern == null) {
            sourceDocumentPattern = new PatternSet().exclude(UNDERSCORE_LED_FILES)
        }
        Closure configuration = (Closure) cfg.clone()
        configuration.delegate = sourceDocumentPattern
        configuration()
    }

    /** Configures sources.
     *
     * @param cfg Configuration {@link org.gradle.api.Action}. Is passed a {@link PatternSet}.
     */
    void sources(final Action<? super PatternSet> cfg) {
        if (sourceDocumentPattern == null) {
            sourceDocumentPattern = new PatternSet().exclude(UNDERSCORE_LED_FILES)
        }
        cfg.execute(sourceDocumentPattern)
    }

    /** Include source patterns.
     *
     * @param includePatterns ANT-style patterns for sources to include
     */
    void sources(String... includePatterns) {
        sources(new Action<PatternSet>() {

            @Override
            void execute(PatternSet patternSet) {
                patternSet.include(includePatterns)
            }
        })
    }

    /** Clears existing sources patterns.
     */
    void clearSources() {
        sourceDocumentPattern = null
    }

    /** Clears any of the existing secondary soruces patterns.
     *
     * This should be used if none of the default patterns should be monitored.
     */
    void clearSecondarySources() {
        secondarySourceDocumentPattern = new PatternSet()
    }

    /** Configures secondary sources.
     *
     * @param cfg Configuration closure. Is passed a {@link PatternSet}.
     */
    @CompileDynamic
    void secondarySources(final Closure cfg) {
        if (this.secondarySourceDocumentPattern == null) {
            this.secondarySourceDocumentPattern = defaultSecondarySourceDocumentPattern
        }
        executeDelegatingClosure(this.secondarySourceDocumentPattern, cfg)
    }

    /** Configures sources.
     *
     * @param cfg Configuration {@link Action}. Is passed a {@link PatternSet}.
     */
    void secondarySources(final Action<? super PatternSet> cfg) {
        if (secondarySourceDocumentPattern == null) {
            secondarySourceDocumentPattern = defaultSecondarySourceDocumentPattern
        }
        cfg.execute(secondarySourceDocumentPattern)
    }

    /** Returns a FileTree containing all of the source documents
     *
     * If a filter with {@link #sources} was never set then all asciidoc source files
     * below {@link #setSourceDir} will be included. If multiple languages are used all
     * of the language source sets be will included.
     *
     * @return Applicable source trees.
     *
     * @since 1.5.1
     */
    @Internal
    FileTree getSourceFileTree() {
        if (languages.empty) {
            getSourceFileTreeFrom(sourceDir)
        } else {
            languages.sum { lang ->
                getLanguageSourceFileTree(lang)
            } as FileTree
        }
    }

    /** Returns a FileTree containing all of the secondary source documents.
     *
     * If a filter with {@link #secondarySources} was never set then all asciidoc source files
     * below {@link #setSourceDir} will be included. If multiple languages are used all
     * of the language secondary source sets be will included.
     *
     * @return Collection of secondary source files
     *
     */
    @Internal
    FileTree getSecondarySourceFileTree() {
        if (languages.empty) {
            getSecondarySourceFileTreeFrom(sourceDir)
        } else {
            languages.sum { lang ->
                getLanguageSecondarySourceFileTree(lang)
            } as FileTree
        }
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * @param cfg {@link CopySpec} runConfiguration closure
     * @since 1.5.1
     */
    void resources(Closure cfg) {
        if (this.resourceCopy == null) {
            this.resourceCopy = project.copySpec(cfg)
        } else {
            Closure configuration = (Closure) cfg.clone()
            configuration.delegate = this.resourceCopy
            configuration()
        }
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * @param cfg {@link CopySpec} runConfiguration {@link Action}
     */
    void resources(Action<? super CopySpec> cfg) {
        if (this.resourceCopy == null) {
            this.resourceCopy = project.copySpec(cfg)
        } else {
            cfg.execute(this.resourceCopy)
        }
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * If not languages are set. these resources will be ignored.
     *
     * @param cfg {@link CopySpec} runConfiguration closure
     * @param lang Language to which these resources will be applied to.
     * @since 3.0.0
     */
    void resources(final String lang, Closure cfg) {
        if (this.languageResources[lang] == null) {
            this.languageResources[lang] = project.copySpec(cfg)
        } else {
            Closure configuration = (Closure) cfg.clone()
            configuration.delegate = this.languageResources[lang]
            configuration()
        }
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * If not languages are set. these resources will be ignored.
     *
     * @param cfg {@link CopySpec} runConfiguration {@link Action}
     * @param lang Language to which these resources will be applied to.
     * @since 3.0.0
     */
    void resources(final String lang, Action<? super CopySpec> cfg) {
        if (this.languageResources[lang] == null) {
            this.languageResources[lang] = project.copySpec(cfg)
        } else {
            cfg.execute(this.languageResources[lang])
        }
    }

    /** Copies all resources to the output directory.
     *
     * Some backends (such as {@code html5}) require all resources to be copied to the output directory.
     * This is the default behaviour for this task.
     */
    void copyAllResources() {
        this.copyResourcesForBackends = []
    }

    /** Do not copy any resources to the output directory.
     *
     * Some backends (such as {@code pdf}) process all resources in place.
     *
     */
    void copyNoResources() {
        this.copyResourcesForBackends = null
    }

    /** Copy resources to the output directory only if the backend names matches any of the specified
     * names.
     *
     * @param backendNames List of names for which resources should be copied.
     *
     */
    void copyResourcesOnlyIf(String... backendNames) {
        this.copyResourcesForBackends = []
        this.copyResourcesForBackends.addAll(backendNames)
    }

    /** List of backends for which to copy resources.
     *
     * @return List of backends. Can be {@code null}.
     */
    @Internal
    Optional<List<String>> getCopyResourcesForBackends() {
        Optional.ofNullable(this.copyResourcesForBackends)
    }

    /**
     * A provider of patterns identifying intermediate artifacts.
     *
     * @return Provider to a {@link PatternSet}. Can be empty.
     */
    @Override
    Provider<PatternSet> getIntermediateArtifactPatternProvider() {
        this.intermediateArtifactPattern
    }

    /**
     * Returns the copy specification for the resources of a specific language.
     *
     * @param lang Language
     *
     * @return Copy specification. Can be {@code null}.
     */
    @Override
    CopySpec getLanguageResourceCopySpec(String lang) {
        languageResources[lang]
    }

    /** Some extensions such as {@code ditaa} creates images in the source directory.
     *
     * Use this setting to copy all sources and resources to an intermediate work directory
     * before processing starts. This will keep the source directory pristine
     */
    void useIntermediateWorkDir() {
        withIntermediateWorkDir = true
    }

    /** The document conversion might generate additional artifacts that could
     * require copying to the final destination.
     *
     * An example is use of {@code ditaa} diagram blocks. These artifacts can be specified
     * in this block. Use of the option implies {@link #useIntermediateWorkDir}.
     * If {@link #copyNoResources} is set or {@link #copyResourcesOnlyIf(String ...)} does not
     * match the backend, no copy will occur.
     *
     * @param cfg Configures a {@link PatternSet} with a base directory of the intermediate working
     * directory.
     */
    void withIntermediateArtifacts(@DelegatesTo(PatternSet) Closure cfg) {
        useIntermediateWorkDir()
        if (!this.intermediateArtifactPattern.present) {
            this.intermediateArtifactPattern.set(new PatternSet())
        }
        executeDelegatingClosure(this.intermediateArtifactPattern.get(), cfg)
    }

    /** Additional artifacts created by Asciidoctor that might require copying.
     *
     * @param cfg Action that configures a {@link PatternSet}.
     *
     * @see {@link #withIntermediateArtifacts(Closure cfg)}
     */
    void withIntermediateArtifacts(final Action<PatternSet> cfg) {
        useIntermediateWorkDir()
        if (!this.intermediateArtifactPattern.present) {
            this.intermediateArtifactPattern.set(new PatternSet())
        }
        cfg.execute(this.intermediateArtifactPattern.get())
    }

    /**
     * Checks whether an intermediate workdir is required.
     *
     * @return {@code true} is there is an intermediate working directory.
     */
    @Override
    boolean hasIntermediateWorkDir() {
        this.intermediateWorkDirProvider.present
    }

    @Override
    Provider<File> getIntermediateWorkDirProvider() {
        this.intermediateWorkDirProvider
    }

    /** The directory that will be the intermediate directory should one be required.
     *
     * @return Intermediate working directory
     *
     * @since 2.2.0
     */
    @Internal
    File getIntermediateWorkDir() {
        this.intermediateWorkDirProvider.get()
    }

    /** Returns a list of all output directories by backend
     *
     * @since 1.5.1
     */
    @OutputDirectories
    Set<File> getBackendOutputDirectories() {
        if (languages.empty) {
            Transform.toSet(configuredOutputOptions.backends) {
                String it -> getOutputDirFor(it)
            }
        } else {
            configuredOutputOptions.backends.collectMany { String backend ->
                Transform.toList(languages) { String lang ->
                    getOutputDirFor(backend, lang)
                }
            }.toSet()
        }
    }

    /** Obtain List of languages the sources documents are written in.
     *
     * @return List of languages. Can be empty, but never {@code null}.
     *
     * @since 3.0.0
     */
    @Input
    List<String> getLanguages() {
        this.languages
    }

    /** Reset current list of languages and replace with a new set.
     *
     * @param langs List of new languages
     *
     * @since 3.0.0
     */
    void setLanguages(Iterable<String> langs) {
        this.languages.clear()
        this.languages.addAll(langs)
    }

    /** Add to list of languages to process.
     *
     * @param langs List of additional languages
     *
     * @since 3.0.0
     */
    void languages(Iterable<String> langs) {
        this.languages.addAll(langs)
    }

    /** Add to list of languages to process.
     *
     * @param langs List of additional languages
     *
     * @since 3.0.0
     */
    void languages(String... langs) {
        this.languages.addAll(langs)
    }

    /** Gets the CopySpec for additional resources.
     *
     * If {@code resources} was never called, it will return a default CopySpec otherwise it will return the
     * one built up via successive calls to {@code resources}
     *
     * @param lang Language to to apply to or empty for no-language support.
     * @return A{@link CopySpec}. Never {@code null}.
     */
    @Override
    CopySpec getResourceCopySpec(Optional<String> lang) {
        this.resourceCopy ?: getDefaultResourceCopySpec(lang)
    }

    /** The default CopySpec that will be used if {@code resources} was never called
     *
     * By default anything below {@code $sourceDir/images} will be included.
     *
     * @param lang Language to use. Can be empty (not {@code null}) when not to use a language.
     * @return A{@link CopySpec}. Never {@code null}.
     */
    @CompileDynamic
    CopySpec getDefaultResourceCopySpec(Optional<String> lang) {
        project.copySpec {
            from(lang.present ? new File(sourceDir, lang.get()) : sourceDir) {
                include 'images/**'
            }
        }
    }

    /**
     * A task may add some default attributes.
     *
     * If the user specifies any of these attributes, then those attributes will not be utilised.
     *
     * The default implementation will add {@code includedir}, {@code revnumber}, {@code gradle-project-group},
     * {@code gradle-project-name}
     *
     * @param workingSourceDir Directory where source files are located.
     *
     * @return A collection of default attributes.
     */
    Map<String, Object> getTaskSpecificDefaultAttributes(File workingSourceDir) {
        Map<String, Object> attrs = [
                includedir: (Object) workingSourceDir.absolutePath
        ]

        String revNumber = defaultRevNumber.get()
        if (!revNumber.empty && revNumber != Project.DEFAULT_VERSION) {
            attrs.put('revnumber', revNumber)
        }

        attrs
    }

    /** Prepares a workspace prior to conversion.
     *
     * @return A presentation of the working source directory and the source tree.
     */
    Workspace prepareWorkspace() {
        if (!this.languages.empty) {
            throw new AsciidoctorMultiLanguageException('Use prepareWorkspace(lang) instead.')
        }
        if (this.withIntermediateWorkDir) {
            File tmpDir = intermediateWorkDir
            prepareTempWorkspace(tmpDir)
            Workspace.builder().workingSourceDir(tmpDir).sourceTree(getSourceFileTreeFrom(tmpDir)).build()
        } else {
            Workspace.builder().workingSourceDir(sourceDir).sourceTree(sourceFileTree).build()
        }
    }

    /** Prepares a workspace for a specific language prior to conversion.
     *
     * @param language Language to prepare workspace for.
     * @return A presentation of the working source directory and the source tree.
     */
    Workspace prepareWorkspace(String language) {
        if (this.withIntermediateWorkDir) {
            File tmpDir = new File(intermediateWorkDir, language)
            prepareTempWorkspace(tmpDir, language)
            Workspace.builder().workingSourceDir(tmpDir).sourceTree(getSourceFileTreeFrom(tmpDir)).build()
        } else {
            File srcDir = new File(sourceDir, language)
            Workspace.builder()
                    .workingSourceDir(srcDir)
                    .sourceTree(getSourceFileTreeFrom(srcDir))
                    .build()
        }
    }

    /** Checks whether an explicit strategy has been set for base directory.
     *
     * @return {@code true} if a strategy has been configured.
     */
    @Internal
    boolean isBaseDirConfigured() {
        this.baseDir != null
    }

    /** Shortcut method for obtaining attributes.
     *
     * In most implementations this will just access the {@code getAttributes} method
     * on the appropriate task extension derived from {@link AbstractImplementationEngineExtension}
     *
     * @return Access to attributes hashmap
     */
    @Input
    abstract Map<String, Object> getAttributes()

    /** Shortcut method to apply a new set of Asciidoctor attributes, clearing any attributes previously set.
     *
     * In most implementations this will just access the {@code setAttributes} method
     * on the appropriate task extension derived from {@link AbstractImplementationEngineExtension}
     *
     * @param m Map with new options
     */
    abstract void setAttributes(Map<String, Object> m)

    /** Shortcut method to add additional asciidoctor attributes.
     *
     * In most implementations this will just access the {@code attributes} method
     * on the appropriate task extension derived from {@link AbstractImplementationEngineExtension}
     *
     * @param m Map with new options
     */
    abstract void attributes(Map<String, Object> m)

    /** Shortcut method to access additional providers of attributes.
     *
     * In most implementations this will just access the {@code getAttributeProviders} method
     * on the appropriate task extension derived from {@link AbstractImplementationEngineExtension}
     *
     * @return List of attribute providers.
     */
    @Internal
    abstract List<AsciidoctorAttributeProvider> getAttributeProviders()

    /** Configurations for which dependencies should be reported.
     *
     * @return Set of configurations. Can be empty, but never {@code null}.
     *
     * @since 2.3.0 (Moved from org.asciidoctor.gradle.jvm)
     */
    @Internal
    abstract Set<Configuration> getReportableConfigurations()

    @SuppressWarnings('ThisReferenceEscapesConstructor')
    protected AbstractAsciidoctorBaseTask() {
        super()
        notCompatibleWithConfigurationCache(
                'Asciidoctor Gradle 4.x is not compatible with CC. Wait for the 5.x release'
        )
        this.projectOperations = ProjectOperations.find(project)
        this.intermediateArtifactPattern = project.objects.property(PatternSet)
        this.srcDir = createDirectoryProperty(project)
        this.outDir = createDirectoryProperty(project)
        this.defaultRevNumber = projectOperations.projectTools.versionProvider.orElse(Project.DEFAULT_VERSION)
        this.intermediateWorkDirProvider = projectOperations.buildDirDescendant(
                "/tmp/${projectOperations.fsOperations.toSafeFileName(this.name)}.intermediate"
        )

        projectOperations.tasks.inputFiles(
                inputs,
                { projectOperations.fsOperations.resolveFilesFromCopySpec(getResourceCopySpec(Optional.empty())) },
                RELATIVE,
                IGNORE_EMPTY_DIRECTORIES
        )
        projectOperations.tasks.inputFiles(
                inputs,
                { sourceFileTree },
                RELATIVE, IGNORE_EMPTY_DIRECTORIES, SKIP_WHEN_EMPTY
        )
        projectOperations.tasks.inputFiles(
                inputs,
                { secondarySourceFileTree },
                RELATIVE, IGNORE_EMPTY_DIRECTORIES
        )
        this.baseDirConfiguration = new DefaultAsciidoctorBaseDirConfiguration(project, this)
    }

    @Nested
    protected OutputOptions getConfiguredOutputOptions() {
        configuredOutputOptions
    }

    /**
     *  Access tp the object that handles base directory configuration
     *
     * @return Implementation of {@link AsciidoctorTaskBaseDirConfiguration}
     *
     * @since 4.0
     */
    @Nested
    protected AsciidoctorTaskBaseDirConfiguration getBaseDirDelegate() {
        this.baseDirConfiguration
    }

    /**
     * Returns the path of one File relative to another.
     *
     * @param target the target directory
     * @param base the base directory
     * @return target's path relative to the base directory
     * @throws IOException if an error occurs while resolving the files' canonical names
     */
    protected String getRelativePath(File target, File base) throws IOException {
        base.toPath().relativize(target.toPath()).toFile().toString()
    }

    /** Group the source files by relative path from the root source directory.
     *
     * @return Map of relative path to set of actual files.
     */
    @Internal
    protected Map<String, List<File>> getSourceFileGroupedByRelativePath() {
        if (languages.empty) {
            File root = sourceDir
            sourceFileTree.files.groupBy { File f ->
                getRelativePath(f.parentFile, root)
            }
        } else {
            throw new AsciidoctorMultiLanguageException('Use getSourceFileGroupedByRelativePath(lang) instead')
        }
    }

    /** Group the source files by relative path from the root source directory.
     *
     * @param lang Language to select
     * @return Map of relative path to set of actual files.
     */
    protected Map<String, List<File>> getSourceFileGroupedByRelativePath(final String lang) {
        File root = new File(sourceDir, lang)
        getSourceFileTreeFrom(root).files.groupBy { File f ->
            getRelativePath(f.parentFile, root)
        }
    }

    /** Obtains a source tree based on patterns.
     *
     * @param dir Toplevel source directory.
     * @return Source tree based upon configured pattern.
     */
    protected FileTree getSourceFileTreeFrom(File dir) {
        AsciidoctorUtils.getSourceFileTree(
                projectOperations,
                dir,
                this.sourceDocumentPattern ?: defaultSourceDocumentPattern
        )
    }

    /** Obtains a secondary source tree based on patterns.
     *
     * @param dir Toplevel source directory.
     * @return Source tree based upon configured pattern.
     */
    protected FileTree getSecondarySourceFileTreeFrom(File dir) {
        Spec<FileTreeElement> primarySourceSpec = (this.sourceDocumentPattern ?: defaultSourceDocumentPattern).asSpec
        project.fileTree(dir)
                .matching(this.secondarySourceDocumentPattern ?: defaultSecondarySourceDocumentPattern)
                .matching { PatternFilterable target ->
                    target.exclude(primarySourceSpec)
                }
    }

    /** The default PatternSet that will be used if {@code sources} was never called
     *
     * @return By default all *.adoc,*.ad,*.asc,*.asciidoc is included.
     *   Files beginning with underscore are excluded
     *
     * @since 1.5.1
     */
    @Internal
    protected PatternSet getDefaultSourceDocumentPattern() {
        asciidocPatterns.exclude UNDERSCORE_LED_FILES
    }

    /** The default pattern set for secondary sources.
     *
     * @return By default all *.adoc,*.ad,*.asc,*.asciidoc is included.
     */
    @Internal
    protected PatternSet getDefaultSecondarySourceDocumentPattern() {
        asciidocPatterns
    }

    /** Adds an input property.
     *
     * Serves as a proxy method in order to deal with the API differences between Gradle 4.0-4.2 and 4.3
     *
     * @param propName Name of property
     * @param value Value of the input property
     */
    @CompileDynamic
    protected void addInputProperty(String propName, Object value) {
        inputs.property(propName, value)
    }

    /** Adds an optional input property.
     *
     * Serves as a proxy method in order to deal with the API differences between Gradle 4.0-4.2 and 4.3
     *
     * @param propName Name of property
     * @param value Value of the input property
     */
    @CompileDynamic
    protected void addOptionalInputProperty(String propName, Object value) {
        inputs.property(propName, value).optional(true)
    }

    /** Copy resources for a backend name.
     *
     * @param backendName Name of backend for which resources are copied
     * @param sourceDir Source directory of resources
     * @param outputDir Final output directory.
     * @param includeLang If set also copy resources for this specified language
     */
    protected void copyResourcesByBackend(
            final String backendName,
            final File sourceDir,
            final File outputDir,
            Optional<String> includeLang
    ) {
        CopySpec rcs = getResourceCopySpec(includeLang)
        logger.info "Copy resources for '${backendName}' to ${outputDir}"

        FileTree ps = this.intermediateArtifactPattern.present ?
                projectOperations.fileTree(sourceDir).matching(this.intermediateArtifactPattern.get()) :
                null

        CopySpec langSpec = includeLang.present ? languageResources[includeLang.get()] : null

        project.copy(new Action<CopySpec>() {
            @Override
            void execute(CopySpec copySpec) {
                copySpec.with {
                    into outputDir
                    with rcs

                    if (ps != null) {
                        from ps
                    }

                    if (langSpec) {
                        with langSpec
                    }
                }
            }
        })
    }

    /**
     * Instance of {@link ProjectOperations} that is attached to this task.
     *
     * @return {@link ProjectOperations} instance.
     */
    @Internal
    protected ProjectOperations getProjectOperations() {
        this.projectOperations
    }

    /** Gets the language-specific source tree
     *
     * @param lang Language
     * @return Language-specific source tree.
     *
     * @since 3.0.0
     */
    protected FileTree getLanguageSourceFileTree(final String lang) {
        getSourceFileTreeFrom(new File(sourceDir, lang))
    }

    /** Gets the language-specific secondary source tree
     *
     * @param lang Language
     * @return Language-specific source tree.
     *
     * @since 3.0.0
     */
    protected FileTree getLanguageSecondarySourceFileTree(final String lang) {
        getSecondarySourceFileTreeFrom(new File(sourceDir, lang))
    }

    /** Convert language list to {@link Optional} list.
     *
     * @return List of languages as {@code Optional<String>}. If languages are not
     *   defined then an array with a single empty optional is returned. Never {@code null}.
     *
     * @since 3.0.0
     */
    @Internal
    protected List<Optional<String>> getLanguagesAsOptionals() {
        if (this.languages.empty) {
            [Optional.empty() as Optional<String>]
        } else {
            Transform.toList(this.languages) { String it ->
                Optional.of(it)
            }
        }
    }

    /** Validates all preconditions prior to starting to run the conversion process.
     *
     */
    protected void validateConditions() {
        checkForInvalidSourceDocuments()
        checkForIncompatiblePathRoots()
    }

    /** Get the output directory for a specific backend.
     *
     * @param backendName Name of backend
     * @return Output directory.
     */
    protected File getOutputDirFor(final String backendName) {
        if (outputDir == null) {
            throw new GradleException("outputDir has not been defined for task '${name}'")
        }
        if (!this.languages.empty) {
            throw new AsciidoctorMultiLanguageException('Use getOutputDir(backendname,language) instead.')
        }
        configuredOutputOptions.separateOutputDirs ? new File(outputDir, backendName) : outputDir
    }

    /** Get the output directory for a specific backend.
     *
     * @param backendName Name of backend
     * @param language Language for which sources are being generated.
     * @return Output directory.
     *
     * @since 3.0.0
     */
    protected File getOutputDirFor(final String backendName, final String language) {
        if (outputDir == null) {
            throw new GradleException("outputDir has not been defined for task '${name}'")
        }
        configuredOutputOptions.separateOutputDirs ?
                new File(outputDir, "${language}/${backendName}") :
                new File(outputDir, language)
    }

    /**
     * Prepare attributes to be serialisable
     *
     * @param workingSourceDir Working source directory from which source documents will be made available.
     * @param seedAttributes Initial attributes set on the task.
     * @param langAttributes Any language specific attributes.
     * @param attributeProviders Additional attribute providers.
     * @param lang Language being processed. Can be unset if multi-language feature is not used.
     * @return Attributes ready for serialisation.
     *
     * @since 3.0.0
     */
    protected Map<String, Object> prepareAttributes(
            final File workingSourceDir,
            Map<String, Object> seedAttributes,
            Map<String, Object> langAttributes,
            List<AsciidoctorAttributeProvider> attributeProviders,
            Optional<String> lang
    ) {
        Map<String, Object> attrs = [:]
        attrs.putAll(seedAttributes)
        attrs.putAll(langAttributes)
        attributeProviders.each {
            attrs.putAll(it.attributes)
        }

        Map<String, Object> defaultAttrs = prepareDefaultAttributes(
                attrs,
                getTaskSpecificDefaultAttributes(workingSourceDir),
                lang
        )
        attrs.putAll(defaultAttrs)
        evaluateProviders(attrs)
    }

    private Map<String, Object> prepareDefaultAttributes(
            Map<String, Object> seedAttributes,
            Map<String, Object> defaultAttributes,
            Optional<String> lang
    ) {
        Set<String> userDefinedAttrKeys = trimOverridableAttributeNotation(seedAttributes.keySet())

        Map<String, Object> defaultAttrs = defaultAttributes.findAll { k, v ->
            !userDefinedAttrKeys.contains(k)
        }.collectEntries { k, v ->
            ["${k}@".toString(), v instanceof Serializable ? v : projectOperations.stringTools.stringize(v)]
        } as Map<String, Object>

        if (lang.present) {
            defaultAttrs.put('lang@', lang.get())
        }

        defaultAttrs
    }

    private Set<String> trimOverridableAttributeNotation(Set<String> attributeKeys) {
        // remove possible trailing '@' character that is used to encode that the attribute can be overridden
        // in the document itself
        Transform.toSet(attributeKeys) { k -> k - ~/@$/ }
    }

    /** Evaluates a map of items potentially containing providers.
     *
     * No recursive evaluation will be performed.
     *
     * @param initialMap Map of items that needs to be searched for providers.
     * @return Map of items with providers evaluated.
     */
    protected Map<String, Object> evaluateProviders(final Map<String, Object> initialMap) {
        initialMap.collectEntries { String k, Object v ->
            if (v instanceof Provider) {
                [k, v.get()]
            } else {
                [k, v]
            }
        } as Map<String, Object>
    }

    private void checkForInvalidSourceDocuments() {
        if (!sourceFileTree.filter { File f ->
            f.name.startsWith('_')
        }.empty) {
            throw new InvalidUserDataException('Source documents may not start with an underscore')
        }
    }

    private void checkForIncompatiblePathRoots() {
        if (outputDir == null) {
            throw new GradleException("outputDir has not been defined for task '${name}'")
        }

        Path baseRoot = languages.empty ? baseDir?.toPath()?.root : getBaseDir(languages[0])?.toPath()?.root
        if (baseRoot != null) {
            Path sourceRoot = sourceDir.toPath().root
            Path outputRoot = outputDir.toPath().root

            if (sourceRoot != baseRoot || outputRoot != baseRoot) {
                throw new AsciidoctorExecutionException(
                        "sourceDir, outputDir and baseDir needs to have the same root filesystem for ${engineName} " +
                                'to function correctly. ' +
                                'This is typically caused on Windows where everything is not on the same drive letter.'
                )
            }
        }
    }

    private void prepareTempWorkspace(final File tmpDir) {
        if (!this.languages.empty) {
            throw new AsciidoctorMultiLanguageException('Use prepareTempWorkspace(tmpDir,lang) instead')
        }
        prepareTempWorkspace(
                tmpDir,
                sourceFileTree,
                secondarySourceFileTree,
                getResourceCopySpec(Optional.empty()),
                Optional.empty()
        )
    }

    private void prepareTempWorkspace(final File tmpDir, final String lang) {
        prepareTempWorkspace(
                tmpDir,
                getLanguageSourceFileTree(lang),
                getLanguageSecondarySourceFileTree(lang),
                getResourceCopySpec(Optional.of(lang)),
                Optional.ofNullable(this.languageResources[lang])
        )
    }

    private void prepareTempWorkspace(
            final File tmpDir,
            final FileTree mainSourceTree,
            final FileTree secondarySourceTree,
            final CopySpec resourceTree,
            final Optional<CopySpec> langResourcesTree
    ) {
        if (tmpDir.exists()) {
            tmpDir.deleteDir()
        }
        tmpDir.mkdirs()
        project.copy { CopySpec cs ->
            cs.with {
                into tmpDir
                from mainSourceTree
                from secondarySourceTree
                with resourceTree
                if (langResourcesTree.present) {
                    with langResourcesTree.get()
                }
            }
        }
    }

    private PatternSet getAsciidocPatterns() {
        PatternSet ps = new PatternSet()
        ps.include '**/*.adoc'
        ps.include '**/*.ad'
        ps.include '**/*.asc'
        ps.include '**/*.asciidoc'
    }
}
