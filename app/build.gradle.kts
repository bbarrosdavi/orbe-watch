import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Os shaders e a arte do orbe moram no orbe-qt do orbe-desktop (o submódulo
// orbe-desktop/) e não são copiados para cá no git: o build leva os .frag (o app converte de GLSL 440 para ES 3.00 ao
// carregar) e os atlas para os assets. Mudou o desenho no desktop, mudou aqui.
abstract class CopiarArte : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val orbeQt: DirectoryProperty

    @get:OutputDirectory
    abstract val saida: DirectoryProperty

    @get:Inject
    abstract val arquivos: FileSystemOperations

    @TaskAction
    fun copiar() {
        arquivos.sync {
            from(orbeQt.dir("shaders")) {
                include("*.frag")
                into("shaders")
            }
            from(orbeQt.dir("arte")) {
                include("*.png")
                into("arte")
            }
            from(orbeQt.dir("comum")) {
                include("anel_atlas.png")
                into("arte")
            }
            into(saida)
        }
    }
}

val copiarArte = tasks.register<CopiarArte>("copiarArte") {
    orbeQt.set(rootProject.layout.projectDirectory.dir("orbe-desktop/orbe-qt"))
    saida.set(layout.buildDirectory.dir("generated/orbe/assets"))
}

android {
    namespace = "io.orbe.watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.orbe.watch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // a bancada (androidTest, com -Pbancada) mede os shaders na GPU do relógio,
    // fora da tela: um pacote à parte, para não tocar no app instalado. Sem a
    // flag, os testes seguem no debug (o AGP 9 só testa o testBuildType)
    if (project.hasProperty("bancada")) testBuildType = "bancada"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // sem loja: o release instala com a chave de depuração, por cima do debug
            signingConfig = signingConfigs.getByName("debug")
        }
        create("bancada") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".bancada"
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // os testes leem os .frag de verdade, do orbe-qt, e deixam os convertidos
                // em build/shaders-es, para conferir num validador (glslangValidator)
                it.systemProperty("orbe.qt", rootProject.layout.projectDirectory.dir("orbe-desktop/orbe-qt").asFile.path)
                it.systemProperty("orbe.despejo", layout.buildDirectory.dir("shaders-es").get().asFile.path)
            }
        }
    }
}

androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(copiarArte, CopiarArte::saida)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // as prévias da tela no Android Studio (src/debug): só no build de debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.wear.compose.foundation)
    // a lista do menu em roda (TransformingLazyColumn com a transformação do sistema), como no HinaWatch
    implementation(libs.wear.compose.material3)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.junit)
}
