## html jex boostrap 

Example uses:
- Webview 
- Jex (JDK HttpServer) as the webserver
- JStachio for SSR html for htmx UI
- Uses Bootstrap 5.3 - https://getbootstrap.com/
- Uses HTMX 

## Interesting notes
- Need buildArg `-H:+SharedArenaSupport`


## To build as a single executable
```shell
mvn package -Pnative
```

## Run it
```shell
./target/htmx-jex-bootstrap -Dlogger.config=logger.properties
```

Override the logging configuration using `-Dlogger.config`
```shell
./target/htmx-jex-bootstrap -Dlogger.config=logger.properties
```

Run it on a specific port via `-Dhttp.port=8092` (rather than use random port). 
With this we can use a normal browser against the http server:
```shell
./target/htmx-jex-bootstrap -Dlogger.config=logger.properties -Dhttp.port=8092
```