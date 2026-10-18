# Keep the app's own classes readable in crash logs; libraries are still optimized.
-keep class dev.k3be.restv.** { *; }
-keepattributes SourceFile,LineNumberTable
