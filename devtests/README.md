# Тесты движка (для разработки, в сборку APK не входят)

Каждый файл — отдельная программа с `main`, гоняет мир без графики и печатает результаты.

Запуск (нужен kotlinc 2.0.21: https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip):

```
# 1) JBox2D в jar (исходники вендорены в core/src/main/java/org/jbox2d)
mkdir -p /tmp/jb && javac -d /tmp/jb $(find core/src/main/java -name '*.java') && jar cf /tmp/jbox2d.jar -C /tmp/jb .
# 2) движок
kotlinc -cp /tmp/jbox2d.jar core/src/main/kotlin/ru/stikhiya/sim/*.kt -d /tmp/sim.jar
# 3) тест (Helpers.kt нужен всем)
kotlinc -cp /tmp/sim.jar:/tmp/jbox2d.jar devtests/Helpers.kt devtests/Float.kt -d /tmp/t.jar
kotlin -cp /tmp/t.jar:/tmp/sim.jar:/tmp/jbox2d.jar FloatKt
```
