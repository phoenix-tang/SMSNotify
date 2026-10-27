import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置从 keystore.properties 读取（该文件与 .jks 均在 .gitignore 中，不会入库）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "phoenix.smsnotify"
    compileSdk = 36

    defaultConfig {
        applicationId = "phoenix.smsnotify"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有 keystore.properties 时退化为未签名产物，不会阻断别人编译
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * 产物改名：默认是 app-release.apk，这里改成「包名-版本名-构建类型.apk」，
 * 例如 smsnotify-1.0-release.apk（取 applicationId 的最后一段，不带 "phoenix." 前缀）。
 *
 * 版本号直接取自 applicationId / versionName，改版本时文件名自动跟着走，
 * 不用在两处维护。outputs 是列表（将来加渠道/ABI 拆分会有多个），统一遍历。
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // phoenix.smsnotify → smsnotify
            val pkg = variant.applicationId.orNull?.substringAfterLast('.') ?: "app"
            val version = output.versionName.orNull ?: "unspecified"
            output.outputFileName.set("$pkg-$version-${variant.name}.apk")
        }
    }
}
