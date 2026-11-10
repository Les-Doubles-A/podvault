# Room utilise ses implémentations générées par KSP ; conserver les classes de base.
-keep class * extends androidx.room.RoomDatabase { *; }

# Conserver les métadonnées requises par Kotlin pour les modèles utilisés par Room.
-keep class com.podvault.app.data.database.entities.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
