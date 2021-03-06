/*
 * This file is part of dependency-check-gradle.
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
 *
 * Copyright (c) 2015 Wei Ma. All Rights Reserved.
 */

package org.owasp.dependencycheck.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.owasp.dependencycheck.Engine
import org.owasp.dependencycheck.data.nexus.MavenArtifact
import org.owasp.dependencycheck.data.nvdcve.DatabaseException
import org.owasp.dependencycheck.dependency.Confidence
import org.owasp.dependencycheck.dependency.Dependency
import org.owasp.dependencycheck.dependency.Identifier
import org.owasp.dependencycheck.dependency.Vulnerability
import org.owasp.dependencycheck.exception.ExceptionCollection
import org.owasp.dependencycheck.exception.ReportException
import org.owasp.dependencycheck.utils.Settings

import static org.owasp.dependencycheck.utils.Settings.KEYS.*

/**
 * Checks the projects dependencies for known vulnerabilities.
 */
abstract class AbstractAnalyze extends DefaultTask {

    @Internal
    def currentProjectName = project.getName()
    @Internal
    def config = project.dependencyCheck
    @Internal
    def settings

    /**
     * Calls dependency-check-core's analysis engine to scan
     * all of the projects dependencies.
     */
    @TaskAction
    analyze() {
        verifySettings()
        initializeSettings()
        def engine = null
        try {
            engine = new Engine(settings)
        } catch (DatabaseException ex) {
            String msg = "Unable to connect to the dependency-check database"
            if (config.failOnError) {
                throw new GradleException(msg, ex)
            } else {
                logger.error(msg)
            }
        }
        if (engine != null) {
            scanDependencies(engine)
            ExceptionCollection exCol = null
            logger.lifecycle("Checking for updates and analyzing vulnerabilities for dependencies")
            try {
                engine.analyzeDependencies()
            } catch (ExceptionCollection ex) {
                if (config.failOnError && ex.isFatal()) {
                    throw new GradleException("Analysis failed.", ex)
                }
                exCol = ex
            }

            logger.lifecycle("Generating report for project ${currentProjectName}")
            try {
                def name = project.getName()
                def displayName = determineDisplayName()
                def groupId = project.getGroup()
                File output = new File(config.outputDirectory)
                engine.writeReports(displayName, groupId, name.toString(), project.getVersion().toString(), output, config.format.toString())
            } catch (ReportException ex) {
                if (config.failOnError) {
                    if (exCol != null) {
                        exCol.addException(ex)
                        throw new GradleException(exCol)
                    } else {
                        throw new GradleException("Error generating the report", ex)
                    }
                } else {
                    logger.error("Error generating the report", ex)
                }
            } finally {
                cleanup(engine)
            }
            showSummary(engine)
            checkForFailure(engine)
            cleanup(engine)
            if (config.failOnError && exCol != null && exCol.getExceptions().size() > 0) {
                throw new GradleException("One or more exceptions occurred during analysis", exCol)
            }
        }
    }

    /**
     * Gets the projects display name. Project.getDisplayName() has been
     * introduced with Gradle 3.3, thus we need to check for the method's
     * existence first. Fallback: use project NAME
     * @return the display name
     */
    def determineDisplayName() {
        project.metaClass.respondsTo(project, "getDisplayName") ?
                project.getDisplayName() : project.getName()
    }

    def verifySettings() {
        if (config.scanConfigurations && config.skipConfigurations) {
            throw new IllegalArgumentException("you can only specify one of scanConfigurations or skipConfigurations")
        }
    }

    /**
     * Initializes the settings object. If the setting is not set the
     * default from dependency-check-core is used.
     */
    def initializeSettings() {
        settings = new Settings()

        settings.setBooleanIfNotNull(AUTO_UPDATE, config.autoUpdate)

        String[] suppressionLists = determineSuppressions(config.suppressionFiles, config.suppressionFile)

        settings.setArrayIfNotEmpty(SUPPRESSION_FILE, suppressionLists)
        settings.setStringIfNotEmpty(HINTS_FILE, config.hintsFile)

        settings.setStringIfNotEmpty(PROXY_SERVER, config.proxy.server)
        settings.setStringIfNotEmpty(PROXY_PORT, "${config.proxy.port}")
        settings.setStringIfNotEmpty(PROXY_USERNAME, config.proxy.username)
        settings.setStringIfNotEmpty(PROXY_PASSWORD, config.proxy.password)
        //settings.setStringIfNotEmpty(CONNECTION_TIMEOUT, connectionTimeout)
        settings.setStringIfNotNull(DATA_DIRECTORY, config.data.directory)
        settings.setStringIfNotEmpty(DB_DRIVER_NAME, config.data.driver)
        settings.setStringIfNotEmpty(DB_DRIVER_PATH, config.data.driverPath)
        settings.setStringIfNotEmpty(DB_CONNECTION_STRING, config.data.connectionString)
        settings.setStringIfNotEmpty(DB_USER, config.data.username)
        settings.setStringIfNotEmpty(DB_PASSWORD, config.data.password)
        settings.setStringIfNotEmpty(CVE_MODIFIED_12_URL, config.cve.url12Modified)
        settings.setStringIfNotEmpty(CVE_MODIFIED_20_URL, config.cve.url20Modified)
        settings.setStringIfNotEmpty(CVE_SCHEMA_1_2, config.cve.url12Base)
        settings.setStringIfNotEmpty(CVE_SCHEMA_2_0, config.cve.url20Base)
        settings.setBooleanIfNotNull(DOWNLOADER_QUICK_QUERY_TIMESTAMP, config.quickQueryTimestamp)

        if (config.cveValidForHours != null) {
            if (config.cveValidForHours >= 0) {
                settings.setInt(CVE_CHECK_VALID_FOR_HOURS, config.cveValidForHours)
            } else {
                throw new InvalidUserDataException("Invalid setting: `validForHours` must be 0 or greater")
            }
        }
        settings.setBooleanIfNotNull(ANALYZER_JAR_ENABLED, config.analyzers.jarEnabled)
        settings.setBooleanIfNotNull(ANALYZER_NUSPEC_ENABLED, config.analyzers.nuspecEnabled)
        settings.setBooleanIfNotNull(ANALYZER_CENTRAL_ENABLED, config.analyzers.centralEnabled)

        settings.setBooleanIfNotNull(ANALYZER_NEXUS_ENABLED, config.analyzers.nexusEnabled)
        settings.setStringIfNotEmpty(ANALYZER_NEXUS_URL, config.analyzers.nexusUrl)
        settings.setBooleanIfNotNull(ANALYZER_NEXUS_USES_PROXY, config.analyzers.nexusUsesProxy)

        settings.setBooleanIfNotNull(ANALYZER_EXPERIMENTAL_ENABLED, config.analyzers.experimentalEnabled)
        settings.setBooleanIfNotNull(ANALYZER_ARCHIVE_ENABLED, config.analyzers.archiveEnabled)
        settings.setStringIfNotEmpty(ADDITIONAL_ZIP_EXTENSIONS, config.analyzers.zipExtensions)
        settings.setBooleanIfNotNull(ANALYZER_ASSEMBLY_ENABLED, config.analyzers.assemblyEnabled)
        settings.setStringIfNotEmpty(ANALYZER_ASSEMBLY_MONO_PATH, config.analyzers.pathToMono)

        settings.setBooleanIfNotNull(ANALYZER_COCOAPODS_ENABLED, config.analyzers.cocoapodsEnabled)
        settings.setBooleanIfNotNull(ANALYZER_SWIFT_PACKAGE_MANAGER_ENABLED, config.analyzers.swiftEnabled)
        settings.setBooleanIfNotNull(ANALYZER_BUNDLE_AUDIT_ENABLED, config.analyzers.bundleAuditEnabled)
        settings.setStringIfNotEmpty(ANALYZER_BUNDLE_AUDIT_PATH, config.analyzers.pathToBundleAudit)

        settings.setBooleanIfNotNull(ANALYZER_PYTHON_DISTRIBUTION_ENABLED, config.analyzers.pyDistributionEnabled)
        settings.setBooleanIfNotNull(ANALYZER_PYTHON_PACKAGE_ENABLED, config.analyzers.pyPackageEnabled)
        settings.setBooleanIfNotNull(ANALYZER_RUBY_GEMSPEC_ENABLED, config.analyzers.rubygemsEnabled)
        settings.setBooleanIfNotNull(ANALYZER_OPENSSL_ENABLED, config.analyzers.opensslEnabled)
        settings.setBooleanIfNotNull(ANALYZER_CMAKE_ENABLED, config.analyzers.cmakeEnabled)
        settings.setBooleanIfNotNull(ANALYZER_AUTOCONF_ENABLED, config.analyzers.autoconfEnabled)
        settings.setBooleanIfNotNull(ANALYZER_COMPOSER_LOCK_ENABLED, config.analyzers.composerEnabled)
        settings.setBooleanIfNotNull(ANALYZER_NODE_PACKAGE_ENABLED, config.analyzers.nodeEnabled)
        settings.setBooleanIfNotNull(ANALYZER_NSP_PACKAGE_ENABLED, config.analyzers.nspEnabled)
    }

    /**
     * Combines the configured suppressionFile and suppressionFiles into a
     * single array.
     *
     * @return an array of suppression file paths
     */
    private String[] determineSuppressions(suppressionFiles, suppressionFile) {
        if (suppressionFile != null) {
            suppressionFiles << suppressionFile
        }
        suppressionFiles
    }
    /**
     * Releases resources and removes temporary files used.
     */
    def cleanup(engine) {
        settings.cleanup(true)
        engine.close()
    }

    /**
     * Loads the projects dependencies into the dependency-check analysis engine.
     */
    abstract scanDependencies(engine)

    /**
     * Displays a summary of the dependency-check results to the build console.
     */
    def showSummary(Engine engine) {
        def vulnerabilities = engine.getDependencies().collect { Dependency dependency ->
            dependency.getVulnerabilities()
        }.flatten()

        logger.lifecycle("Found ${vulnerabilities.size()} vulnerabilities in project ${currentProjectName}")
        if (config.showSummary) {
            final StringBuilder summary = new StringBuilder()
            for (Dependency d : engine.getDependencies()) {
                boolean firstEntry = true
                final StringBuilder ids = new StringBuilder()
                for (Vulnerability v : d.getVulnerabilities()) {
                    if (firstEntry) {
                        firstEntry = false
                    } else {
                        ids.append(", ")
                    }
                    ids.append(v.getName())
                }
                if (ids.length() > 0) {
                    summary.append(d.getFileName()).append(" (")
                    firstEntry = true
                    for (Identifier id : d.getIdentifiers()) {
                        if (firstEntry) {
                            firstEntry = false
                        } else {
                            summary.append(", ")
                        }
                        summary.append(id.getValue())
                    }
                    summary.append(") : ").append(ids).append('\n')
                }
            }
            if (summary.length() > 0) {
                final String msg = String.format("%n%n"
                        + "One or more dependencies were identified with known vulnerabilities:%n%n%s"
                        + "%n%nSee the dependency-check report for more details.%n%n", summary.toString())
                logger.lifecycle(msg)
            }
        }
    }

    /**
     * If configured, fails the build if a vulnerability is identified with a CVSS
     * score higher then the failure threshold configured.
     */
    def checkForFailure(Engine engine) {
        if (config.failBuildOnCVSS > 10) {
            return
        }

        def vulnerabilities = engine.getDependencies().collect { Dependency dependency ->
            dependency.getVulnerabilities()
        }.flatten()

        final StringBuilder ids = new StringBuilder()

        vulnerabilities.each {
            if (it.getCvssScore() >= config.failBuildOnCVSS) {
                if (ids.length() == 0) {
                    ids.append(it.getName())
                } else {
                    ids.append(", ").append(it.getName())
                }
            }
        }
        if (ids.length() > 0) {
            final String msg = String.format("%n%nDependency-Analyze Failure:%n"
                    + "One or more dependencies were identified with vulnerabilities that have a CVSS score greater then '%.1f': %s%n"
                    + "See the dependency-check report for more details.%n%n", config.failBuildOnCVSS, ids.toString())
            throw new GradleException(msg)
        }

    }

    /**
     * Checks whether the given configuration should be scanned
     * because either scanConfigurations is empty or it contains the
     * configuration's name.
     */
    def shouldBeScanned(configuration) {
        !config.scanConfigurations || config.scanConfigurations.contains(configuration.name)
    }

    /**
     * Checks whether the given configuration should be skipped
     * because skipConfigurations contains the configuration's name.
     */
    def shouldBeSkipped(configuration) {
        config.skipConfigurations.contains(configuration.name)
    }

    /**
     * Checks whether the given configuration should be skipped
     * because it is a test configuration and skipTestGroups is true.
     */
    def shouldBeSkippedAsTest(configuration) {
        config.skipTestGroups && isTestConfiguration(configuration)
    }

    def isTestConfiguration(configuration) {
        def isTestConfiguration = isTestConfigurationCheck(configuration)

        def hierarchy = configuration.hierarchy.collect({ it.name }).join(" --> ")
        logger.info("'{}' is considered a test configuration: {}", hierarchy, isTestConfiguration)

        isTestConfiguration
    }

    /**
     * Checks whether a configuration is considered to be a test configuration in order to skip it.
     * A configuration is considered a test configuration if and only if any of the following conditions holds:
     * <ul>
     *     <li>the name of the configuration or any of its parent configurations equals 'testCompile'</li>
     *     <li>the name of the configuration or any of its parent configurations equals 'androidTestCompile'</li>
     *     <li>the configuration name starts with 'test'</li>
     *     <li>the configuration name starts with 'androidTest'</li>
     * </ul>
     */
    static isTestConfigurationCheck(configuration) {
        def isTestConfiguration = configuration.name.startsWith("test") || configuration.name.startsWith("androidTest")
        configuration.hierarchy.each {
            isTestConfiguration |= (it.name == "testCompile" || it.name == "androidTestCompile")
        }
        isTestConfiguration
    }

    def canBeResolved(configuration) {
        // Configuration.isCanBeResolved() has been introduced with Gradle 3.3,
        // thus we need to check for the method's existence first
        configuration.metaClass.respondsTo(configuration, "isCanBeResolved") ?
                configuration.isCanBeResolved() : true
    }

    /**
     * Adds additional information and evidence to the dependencies.
     * @param deps the list of dependencies that will be updated
     * @param artifact the artifact that was scanned to obtain the dependencies
     * @param configurationName the configuration name that the artifact was identified in
     */
    protected void addInfoToDependencies(List<Dependency> deps, ResolvedArtifact artifact, String configurationName) {
        if (deps != null) {
            if (deps.size() == 1) {
                def d = deps.get(0)
                MavenArtifact mavenArtifact = createMavenArtifact(artifact)
                d.addAsEvidence("gradle", mavenArtifact, Confidence.HIGHEST)
                if (artifact.moduleVersion.id.group != null && artifact.moduleVersion.id.name != null && artifact.moduleVersion.id.version != null) {
                    d.addIdentifier("maven", String.format("%s:%s:%s",
                            artifact.moduleVersion.id.group, artifact.moduleVersion.id.name, artifact.moduleVersion.id.version),
                            null, Confidence.HIGHEST)
                }
                d.addProjectReference(configurationName)
            } else {
                deps.forEach { it.addProjectReference(configurationName) }
            }
        }
    }

    /**
     * Creates a MavenArtifact from the Gradle artifact.
     * @param artifact the Gradle artifact
     * @return the MavenArtifact
     */
    private MavenArtifact createMavenArtifact(ResolvedArtifact artifact) {
        def id = artifact.moduleVersion.id
        new MavenArtifact(id.group, id.name, id.version)
    }
}
