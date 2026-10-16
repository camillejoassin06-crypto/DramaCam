# Regles R8 pour le build release.
#
# Compose, AndroidX, Navigation, WorkManager et Coil embarquent leurs propres
# regles (consumer rules) : rien a ajouter pour eux.

# Les modeles du catalogue seront desserialises depuis catalog.json par
# StaticCatalogRepository (brief suivant). Tant que la desserialisation passe
# par un parseur reflectif, le nom des classes ET celui des champs doivent
# survivre a R8.
#
# `-keepclassmembers` seul ne suffit pas : il protege les membres mais laisse
# renommer la classe qui les porte. Il faut `-keep`.
-keep class com.dramaforge.app.catalog.** {
    <fields>;
    <init>(...);
}

# Le SDK Anthropic serialise ses requetes et desserialise ses reponses par
# reflexion Jackson. R8 renommerait ses champs et casserait la couche HTTP —
# en release uniquement, donc invisible en debug. On conserve l'ensemble.
#
# Les modeles de generation de l'app (com.dramaforge.app.generation) n'ont pas
# besoin de regle : leur mapping JSON est ecrit a la main avec des cles
# explicites, sans reflexion.
-keep class com.anthropic.** { *; }
-dontwarn com.anthropic.**

-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.**

# Le SDK embarque jsonschema-generator (victools), utilise par les SORTIES
# STRUCTUREES. Cette bibliotheque appelle java.lang.reflect.AnnotatedType et
# AnnotatedParameterizedType, qui n'existent pas dans le SDK Android.
#
# C'est du code mort ici : la generation demande le JSON par le prompt et le
# parse a la main (cf. ScriptGenerator). Si un jour on passe aux sorties
# structurees `.outputConfig(Class)`, elles compileront mais planteront au
# runtime sur l'appareil — ce n'est pas qu'un avertissement a faire taire.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.github.victools.**

# OkHttp et ses dependances optionnelles, absentes sur Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Les annotations et signatures generiques sont necessaires a Jackson pour
# reconstruire les types parametres (List<T>, Optional<T>).
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Garder les noms de fichiers et numeros de ligne dans les traces de pile :
# sans cela un crash sur l'APK release est illisible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
