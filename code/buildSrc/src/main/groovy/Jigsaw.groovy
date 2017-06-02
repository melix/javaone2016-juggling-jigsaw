import groovy.transform.CompileStatic
import groovy.transform.CompileDynamic
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.api.ApiJar
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.SourceSet
import org.gradle.api.plugins.JavaPluginConvention

@CompileStatic
class Jigsaw implements Plugin<Project> {

    private final static Attribute<String> API = Attribute.of('api', String)
    private final static Attribute<String> PLATFORM = Attribute.of('platform', String)

    void apply(Project project) {
        ApiExtension apiExtension = (ApiExtension) project.extensions.create("api", ApiExtension)
        def configurer = new Configurer(project)
        PlatformsExtension platformsExtension = (PlatformsExtension) project.extensions.create("platforms", PlatformsExtension, configurer)
        //platformsExtension.targetPlatforms("java${JavaVersion.current().majorVersion}".toString())
    }

    public static class Configurer {
        final Project project

        public Configurer(Project project) {
            this.project = project
        }

        ApiExtension getApiExtension() {
            (ApiExtension) project.extensions.getByName('api')
        }

        PlatformsExtension getPlatformsExtension() {
            (PlatformsExtension) project.extensions.getByName('platforms')
        }

        @CompileDynamic // Groovy bug
        public void configurePlatform(String platform) {
            project.convention.getPlugin(JavaPluginConvention).sourceSets.each { SourceSet sourceSet ->
               def taskName = sourceSet.getCompileJavaTaskName()
               doConfigurePlatform((JavaCompile) project.tasks.getByName(taskName), platform, sourceSet)
            }
        }

        private void doConfigurePlatform(JavaCompile compileTask, String platform, SourceSet sourceSet) {
            def capitalizedPlatform = platform.capitalize()
            if (project.configurations.findByName("compileClasspath${ capitalizedPlatform}")) {
               return
            }
            
            def compileConfiguration = project.configurations.getByName('compile')
            def taskName = "${compileTask.name}$capitalizedPlatform"
            def compilePlatformConfiguration = project.configurations.create("compileClasspath${capitalizedPlatform}")
            compilePlatformConfiguration.attributes { it.attribute(API, 'api'); it.attribute(PLATFORM, platform) }
            compilePlatformConfiguration.extendsFrom compileConfiguration
            def runtimePlatformConfiguration = project.configurations.create("runtime${capitalizedPlatform}")
            runtimePlatformConfiguration.attributes { it.attribute(API, 'runtime'); it.attribute(PLATFORM, platform) }
            runtimePlatformConfiguration.extendsFrom compileConfiguration
            def platformCompile = project.tasks.create(taskName, JavaCompile, new Action<JavaCompile>() {
                @Override
                void execute(final JavaCompile task) {
                    task.options.fork = true
                    def level = "1.${platform - 'java'}"
                    String jdkHome = getPlatformsExtension().jdkFor(platform)
                    task.options.forkOptions.javaHome = new File(jdkHome)
                    task.sourceCompatibility = level
                    task.targetCompatibility = level
                    task.source(compileTask.source)
                    task.source(project.file("src/main/$platform"))
                    task.destinationDir = project.file("$project.buildDir/classes/$platform/$taskName")
                    task.classpath = compilePlatformConfiguration
                }
            })
            def apiJar = project.tasks.create("${taskName}ApiJar", ApiJar, { ApiJar apiJar ->
                apiJar.outputFile = project.file("$project.buildDir/api/${project.name}-${taskName}.jar")
                apiJar.inputs.dir(platformCompile.destinationDir).skipWhenEmpty()
                apiJar.exportedPackages = apiExtension.exports
                apiJar.dependsOn(platformCompile)
            } as Action)
            project.artifacts.add(compilePlatformConfiguration.name, [file: apiJar.outputFile, builtBy: apiJar])

            if (!compileTask.name.contains('Test')) {
                def jar = project.tasks.create("${platform}Jar", Jar) { Jar jar ->
                    jar.from project.files(platformCompile.destinationDir)
                    jar.destinationDir = project.file("$project.buildDir/libs")
                    jar.classifier = platform
                    jar.dependsOn(platformCompile)
                }
                project.tasks.getByName('build').dependsOn jar
                project.artifacts.add(runtimePlatformConfiguration.name, [file: jar.archivePath, builtBy: jar])
            }

            if (platform=='java9' && apiExtension.moduleName) {
                addJigsawModuleFile(taskName, platformCompile, apiExtension, sourceSet)
            }
        }

        private void addJigsawModuleFile(String taskName, JavaCompile platformCompile, ApiExtension extension, SourceSet sourceSet) {
            println "Jigsaw enabled"
            def genDir = new File("$project.buildDir/generated-sources/${taskName}/src/main/jigsaw")
            platformCompile.source(project.files(genDir))
            platformCompile.inputs.properties(exports: extension.exports)
            platformCompile.options.compilerArgs.addAll(['--module-path', platformCompile.classpath.asPath, '--source-path', "$genDir:${sourceSet.java.srcDirs.join(':')}".toString() ])
            platformCompile.doFirst {
                genDir.mkdirs()
                def requires = project.configurations.getByName('compile').files.collect { "   requires ${automaticModule(it.name)};" }.join('\n')
                def exports = extension.exports.collect { "    exports $it;" }.join('\n')
                new File(genDir, 'module-info.java').write("""module ${extension.moduleName} {
${requires}                
${exports}
}""")
            }
        }
        
        private static String automaticModule(String name) {
             int idx = name.lastIndexOf('-');
             if (idx>0) { name = name.substring(0,idx) }
             name.replace('-', '.')
        }
    }


}
