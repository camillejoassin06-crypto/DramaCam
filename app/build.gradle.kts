buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.squareup:javapoet:1.13.0") }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.dagger.hilt.android") version "2.53" apply false
}
