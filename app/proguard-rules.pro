# 混淆规则（minifyEnabled=true 时生效）
# 保持 Compose 相关类不被混淆
-keep class androidx.compose.** { *; }
