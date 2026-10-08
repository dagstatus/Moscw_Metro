import java.util.Properties

plugins { id("com.android.application") }

fun props(path: String) = Properties().apply {
    val f = rootProject.file(path)
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}
val version = props("version.properties")
val appCfg = props("app.properties")
val ks = props("keystore/keystore.properties")

android {
    namespace = "ru.moscowmetro.offline"
    compileSdk = 36

    defaultConfig {
        applicationId = appCfg.getProperty("applicationId", "ru.moscowmetro.offline")
        minSdk = appCfg.getProperty("minSdk", "26").toInt()
        targetSdk = appCfg.getProperty("targetSdk", "35").toInt()
        versionCode = version.getProperty("versionCode", "1").toInt()
        versionName = version.getProperty("versionName", "1.0")
    }

    signingConfigs {
        create("release") {
            if (!ks.isEmpty) {
                storeFile = rootProject.file("keystore/" + ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
