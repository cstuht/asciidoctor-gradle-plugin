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
package org.asciidoctor.gradle.remote

import groovy.transform.CompileStatic
import org.asciidoctor.Options
import org.asciidoctor.ast.Cursor
import org.asciidoctor.gradle.internal.ExecutorConfiguration
import org.asciidoctor.gradle.internal.ExecutorConfigurationContainer
import org.asciidoctor.gradle.internal.ExecutorLogLevel
import org.asciidoctor.log.LogHandler
import org.asciidoctor.log.LogRecord
import org.asciidoctor.log.Severity

import java.util.regex.Pattern

/** Base class for building claspath-isolated executors for Asciidoctor.
 *
 * @since 2.0.0
 *
 * @author Schalk W. Cronjé
 */
@CompileStatic
abstract class ExecutorBase {
    private final List<String> warningMessages = []
    private final List<Pattern> messagePatterns = []
    private final AsciidoctorJSetup setup = new AsciidoctorJSetup()
    protected int maxSeverityLevel = 0 // DEBUG
    protected int failureLevel = 4 // FATAL

    /**  List of configurations that are required for execution.
     *
     */
    protected final List<ExecutorConfiguration> runConfigurations

    /** Initialise the executor.
     *
     * @param execConfig Container of executor configurations
     */
    protected ExecutorBase(final ExecutorConfigurationContainer execConfig) {
        this.runConfigurations = execConfig.configurations
    }

    /** Normalises Asciidoctor options for a given source file.
     *
     * Relativizes certain attributes and ensure specific options for backend, sage mode and output
     * directory are in place.
     *
     * @param file Source file to be converted
     * @param runConfiguration The current executor configuration
     * @return Asciidoctor options
     */
    @SuppressWarnings('DuplicateStringLiteral ')
    protected
    Options normalisedOptionsFor(final File file, ExecutorConfiguration runConfiguration) {
        setup.normalisedOptionsFor(file, runConfiguration)
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
        setup.getRelativePath(target, base)
    }

    /** Rehydrates docExtensions that were serialised.
     *
     * @param registry Asciidoctor GroovyDSL registry instance.
     * @param exts List of docExtensions to rehydrate.
     * @return
     */
    protected List<Object> rehydrateExtensions(final Object registry, final List<Object> exts) {
        setup.rehydrateExtensions(registry, exts)
    }

    /** Creates a log handler for Asciidoctor
     *
     * @param required The required level of logging
     * @return A log handler instance suitable for registering with AsciidoctorJ.
     */
    protected LogHandler getLogHandler(ExecutorLogLevel required) {
        int requiredLevel = required.level
        new LogHandler() {
            @Override
            void log(LogRecord logRecord) {
                ExecutorLogLevel logLevel = LogSeverityMapper.translateAsciidoctorLogLevel(logRecord.severity)
                if (logLevel.level > maxSeverityLevel) {
                    maxSeverityLevel = logLevel.level
                }
                if (logLevel.level >= requiredLevel) {
                    String msg = logRecord.message
                    Cursor cursor = logRecord.cursor
                    if (cursor) {
                        final String cPath = cursor.path ?: ''
                        final String cDir = cursor.dir ?: ''
                        final String cFile = cursor.file ?: ''
                        final String cLine = cursor.lineNumber >= 0 ? cursor.lineNumber.toString() : ''
                        msg = "${msg} :: ${cPath} :: ${cDir}/${cFile}:${cLine}"
                    }
                    if (logRecord.sourceFileName) {
                        final String subMsg = logRecord.sourceMethodName ? (':' + logRecord.sourceMethodName) : ''
                        msg = "${msg} (${logRecord.sourceFileName}${subMsg})"
                    }
                    logMessage(logLevel, msg)
                }

                addMatchingMessage(logRecord.message)
            }
        }
    }

    /** Performs the actual logging of a message.
     *
     * it calls an implementation specifc to the kind of executor to log the message.
     *
     * @param logLevel The level of the message
     * @param msg Message to be logged
     */
    abstract protected void logMessage(ExecutorLogLevel logLevel, final String msg)

    /** Adds a warning message that fits a pattern.
     *
     * @param msg
     */
    protected void addMatchingMessage(final String msg) {
        if (!this.messagePatterns.empty) {
            if (this.messagePatterns.any { msg =~ it }) {
                this.warningMessages.add msg
            }
        }
    }

    /** The list of warning messages that was recorded during the conversion.
     *
     * @return
     */
    protected List<String> getWarningMessages() {
        this.warningMessages
    }

    /** Patterns for matching log messages as errors
     *
     * @param patterns List of patterns. Can be empty.
     */
    protected void resetMessagePatternsTo(final List<Pattern> patterns) {
        this.messagePatterns.clear()
        this.messagePatterns.addAll(patterns)
    }

    /** If any warning message was set, fail with an exception.
     *
     */
    @SuppressWarnings('UnnecessaryGString')
    protected void failOnWarnings() {
        if (!warningMessages.empty) {
            final String msg = "ERROR: The following messages from AsciidoctorJ are treated as errors:\n" +
                    warningMessages.join("\n- ")
            throw new AsciidoctorRemoteExecutionException(msg)
        }
    }

    /** If failure level is reached or exceed, fail with an exception.
     *
     */
    protected void failOnFailureLevelReachedOrExceeded() {
        if (maxSeverityLevel >= failureLevel) {
            Severity maxSeverity = LogSeverityMapper.getSeverityOf(maxSeverityLevel)
            Severity failureSeverity = LogSeverityMapper.getSeverityOf(failureLevel)
            throw new AsciidoctorRemoteExecutionException('ERROR: Failure level reached or exceeded: ' +
                    "${maxSeverity} >= $failureSeverity")
        }
    }

    protected int findHighestFailureLevel(Iterable<Integer> levels) {
        levels.min() as int
    }
}
