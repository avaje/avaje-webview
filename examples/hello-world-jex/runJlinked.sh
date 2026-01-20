mvn clean package -P jlink
./target/jlink-image/bin/java -m webview.example
