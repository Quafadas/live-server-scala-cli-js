# Routes

Files are served under three seperate routing strategies. Conflicts are resolved in the order listed here.

## `--out-dir` Route

These files are assumed to be created by a build tool emitting `.js`, `.js.map`, `.wasm`, and `.wasm.map` files. They are watched in the directory specified by the `--out-dir` argument, and served at the root of the server. For example, scala-js will usually emit a file called `main.js`.

You'll find it at http://localhost:8080/main.js


## SPA Routes

For things like client side routing to work, the backend must return the `index.html` file for any route that doesn't match a static asset. This is done by specifying a `--client-routes-prefix` argument. For example, if you specify `--client-routes-prefix app`, then any request to http://localhost:8080/app/anything/here will return the `index.html` file in response to a request made by the browser. The browser will then handle the routing / rendering.

## Static Routes

Static assets are served from the directory specified by the `--path-to-index-html` argument to the root of the site. For example, if you have a file `styles/index.less` in that directory, it will be served at http://localhost:8080/styles/index.less
