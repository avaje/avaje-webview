## html jex boostrap 

Example uses:
- Webview 
- Jex (JDK HttpServer) as the webserver
- JStachio for SSR html for htmx UI
- Uses Bootstrap 5.3 - https://getbootstrap.com/
- Uses HTMX 

## Interesting notes
- Need  buildArg -H:+SharedArenaSupport


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