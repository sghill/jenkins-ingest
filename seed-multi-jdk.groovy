def workspaceDir = new File(__FILE__).getParentFile()

folder('ingest-multi-jdk') {
    displayName('Multi-JDK Ingest Jobs')
}

configFiles {
    groovyScript {
        id("moderne-gradle-init")
        name("init.gradle")
        comment("A Gradle init script used to inject universal plugins into a gradle build.")
        content readFileFromWorkspace('gradle/init.gradle')
    }
    xmlConfig {
        id("gradle-enterprise-xml")
        name("Gradle Enterprise Maven Configuration")
        comment("A gradle-enterprise.xml file that defines how to connect to ge.openrewrite.org")
        content readFileFromWorkspace('maven/gradle-enterprise.xml')
    }
    xmlConfig {
        id("ingest-maven-settings-xml")
        name("Ingest Maven Settings")
        comment("A maven settings file that sets mirror on repos that at know to use http")
        content readFileFromWorkspace('maven/ingest-maven-settings.xml')
    }
}
new File(workspaceDir, 'repos-multi-jdk.csv').splitEachLine(',') { tokens ->
    if (tokens[0].startsWith('repoName')) {
        return
    }
    def repoName = tokens[0]
    def repoBranch = tokens[1]
    def repoJavaVersion = tokens[2]
    def repoStyle = tokens[3]
    def repoBuildTool = tokens[4]
    def repoJobName = repoName.replaceAll('/', '_')

    println("creating job $repoJobName")
    // TODO figure out how to store rewrite version, look it up on next run, and if rewrite hasn't changed and commit hasn't changed, don't run.
    job("ingest-multi-jdk/$repoJobName") {

        label('multi-jdk')

        scm {
            git {
                remote {
                    url("https://github.com/${repoName}")
                    branch(repoBranch)
                    credentials('jkschneider-pat')
                }
                extensions {
                    localBranch(repoBranch)
                }
            }
        }

        triggers {
            cron('H 4 * * *')
        }

        wrappers {
            credentialsBinding {
                usernamePassword('ARTIFACTORY_USER', 'ARTIFACTORY_PASSWORD', 'artifactory')
                string('GRADLE_ENTERPRISE_ACCESS_KEY', 'gradle-enterprise-access-key')
            }
            timeout {
                absolute(60)
                abortBuild()
            }
            if (['gradle', 'gradlew'].contains(repoBuildTool)) {
                configFiles {
                    file('moderne-gradle-init') {
                        targetLocation('moderne-init.gradle')
                    }
                }
            }
            if (['maven', 'mvnw'].contains(repoBuildTool)) {
                configFiles {
                    file('gradle-enterprise-xml') {
                        targetLocation('.mvn/gradle-enterprise.xml')
                    }
                    file('ingest-maven-settings-xml') {
                        targetLocation('.mvn/ingest-maven-settings.xml')
                    }
                }
            }
        }

        steps {
            sh(
"""
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
jenv global ${repoJavaVersion}
""")
        }

        steps {
            if (['gradle', 'gradlew'].contains(repoBuildTool)) {
                gradle {
                    if (repoBuildTool == 'gradle') {
                        useWrapper(false)
                        gradleName('gradle 7.4.2')
                    } else {
                        useWrapper(true)
                        makeExecutable(true)
                    }
                    if (repoStyle != null) {
                        switches("--no-daemon -Dskip.tests=true -DactiveStyle=${repoStyle} -I moderne-init.gradle")
                    } else {
                        switches('--no-daemon -Dskip.tests=true -I moderne-init.gradle')
                    }
                    tasks('publishModernePublicationToMavenRepository')
                }
            }
        }

        if (['maven', 'mvnw'].contains(repoBuildTool)) {
            // A step that runs before the maven build to setup the gradle enterprise extension.
            steps {
                // Adds a shell script into the Jobs workspace in /tmp.
                // We should add the 'add-gradle-enterprise-extension.sh' and reference that in the shell method.
                shell(
'''
MVN_DIR=".mvn"
EXT_FILE="extensions.xml"
MVN_EXT_XML="<extension><groupId>com.gradle</groupId><artifactId>gradle-enterprise-maven-extension</artifactId><version>1.14.2</version></extension>"

# Create the .mvn directory if it does not exist.
if [ ! -d "$MVN_DIR" ]; then
    mkdir "$MVN_DIR"
fi

# Create the extensions.xml file if it does not exist.
if [ ! -f "$MVN_DIR/$EXT_FILE" ]; then
    touch "$MVN_DIR/$EXT_FILE"
    # Add the `<extensions>` tag.
    echo "<extensions>" >> "$MVN_DIR/$EXT_FILE"
    echo "</extensions>" >> "$MVN_DIR/$EXT_FILE"
    ADD=$(echo $MVN_EXT_XML | sed 's/\\//\\\\\\//g')
    sed -i "/<\\/extensions>/ s/.*/${ADD}\\n&/" "$MVN_DIR/$EXT_FILE"
else
    # Assumes the <extensions> already exists and the <extension> tag does exist.
    ADD=$(echo $MVN_EXT_XML | sed 's/\\//\\\\\\//g')
    sed -i "/<\\/extensions>/ s/.*/${ADD}\\n&/" "$MVN_DIR/$EXT_FILE"
fi
''')
            }
            configure { node ->

                node / 'builders' << 'org.jfrog.hudson.maven3.Maven3Builder' {
                    mavenName 'maven 3'
                    useWrapper(repoBuildTool == 'mvnw')
                    if (repoStyle != null) {
                        goals '-B -Drat.skip=true -Dmaven.findbugs.enable=false -Dspotbugs.skip=true -Dfindbugs.skip=true -DskipTests -DskipITs -Dcheckstyle.skip=true -Denforcer.skip=true -s .mvn/ingest-maven-settings.xml -Drewrite.activeStyles=${repoStyle} -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn install io.moderne:moderne-maven-plugin:0.11.2:ast'
                    } else {
                        goals '-B -Drat.skip=true -Dmaven.findbugs.enable=false -Dspotbugs.skip=true -Dfindbugs.skip=true -DskipTests -DskipITs -Dcheckstyle.skip=true -Denforcer.skip=true -s .mvn/ingest-maven-settings.xml -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn install io.moderne:moderne-maven-plugin:0.11.2:ast'
                    }
                }

                node / 'buildWrappers' << 'org.jfrog.hudson.maven3.ArtifactoryMaven3Configurator' {
                    deployArtifacts true
                    artifactDeploymentPatterns {
                        includePatterns '*-ast.*'
                    }
                    deployerDetails {
                        artifactoryName 'moderne-artifactory'
                        deployReleaseRepository {
                            keyFromText 'moderne-public-ast'
                        }
                        deploySnapshotRepository {
                            keyFromText 'moderne-public-ast'
                        }
                    }
                }
            }
        }

        publishers {
            cleanWs()
        }
    }
    return
}