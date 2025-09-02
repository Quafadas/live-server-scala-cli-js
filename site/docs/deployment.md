Deployment
---

This project targets the dev loop. When comes to deploy however, I always hit the same problem. From discord;


> Ah, yeah, the classic SPA problem. While you develop, everything works because vite/react-scripts/... takes care of it. And    when you deploy, everything seems to work fine, because navigation works via the history object and doesn't really send new   requests. Only if you reload the page are you getting the 404. At least for modern SPAs and frameworks. There also was that   brief period where most SPAs used the fragment for navigation, which didn't have that problem.
>
> The main thing complicating things is that - in most cases - you also need to serve resources, so neither "proxy /api, everything else to index.html" nor "everything /app to index html, rest proxied" work directly.
>
> What I've seen a few times is:

>     - Everything at /api gets proxied
>     - Everything else is resolved as is
>     - index.html is the fallback document that you get instead of a 404.

This project provides a tiny helper library and an opinionated strategy to get over this hurdle, following exacltly the strategy laid out above.

# Scala-cli

I don't think it's possible run client side routing out of scala CLI, as you need to have control over the backend. However, it's easily possible to deploy a static site build with scalaJS to github pages.

buildJs
```sh
  mkdir -p {{outDir}}
  scala-cli --power package . -o {{outDir}} -f --js-mode release
```





Yaml

```yaml
name: Continuous Integration

on:
  push:
    branches: ['main']

env:
  GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  ACTIONS_STEP_DEBUG: true

concurrency:
  group: ${{ github.workflow }} @ ${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: build
    if: github.ref == 'refs/heads/main'
    strategy:
      matrix:
        os: [ubuntu-latest]
    runs-on: ${{ matrix.os }}
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0
      - uses: coursier/cache-action@v6.3
      - uses: VirtusLab/scala-cli-setup@main
        with:
          power: true
          jvm: temurin:21
      - uses: taiki-e/install-action@just
      - name: Setup Pages
        uses: actions/configure-pages@v4
      - run: just buildJs
      - run: just copyAssets
      - name: Setup Pages
        uses: actions/configure-pages@v4
      - uses: actions/upload-artifact@v3
        with:
          name: page
          path: /home/runner/work/indigoLite/indigoLite/.out/
          if-no-files-found: error
  deploy:
    needs: build
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
    - uses: actions/download-artifact@v3
      with:
        name: page
        path: .
    - uses: actions/configure-pages@v4
    - uses: actions/upload-pages-artifact@v2
      with:
        path: .
    - name: Deploy to GitHub Pages
      id: deployment
      uses: actions/deploy-pages@v3
```


# Mill

In build.sc, create new tasks for assembly, that put the JS and static assets in the resources.

```scala sc:nocompile
object backend extends Common with ScalafmtModule with ScalafixModule {
  def frontendResources = T{PathRef(frontend.fullLinkJS().dest.path)}

  def staticAssets = T.source{PathRef(frontend.millSourcePath / "ui")} // index.html is here

  def allClasspath = T{localClasspath() ++ Seq(frontendResources()) ++ Seq(staticAssets())  }

  override def assembly: T[PathRef] = T{
    Assembly.createAssembly(
      Agg.from(allClasspath().map(_.path)),
      manifest(),
      prependShellScript(),
      Some(upstreamAssembly2().pathRef.path),
      assemblyRules
    )
  }

  def ivyDeps = super.ivyDeps() ++ Seq(ivy"io.github.quafadas::frontend-routes:{{projectVersion}}")
}

```

For the server, we then need to setup some routes, which reference this;

```

```

This is aliased as

```scala sc:nocompile
val allFrontendRoutes = io.github.quafadas.sjsls.defaultFrontendRoutes[IO]("ui")
```

For convience. It is not hard to track back through this code to see what's it's doing - it the JS from the server root, and any time it detects a request beginning with `ui`, simply returns `index.html`.

Remember - there is no misdirection as is common with bundlers and whatnot. Use the browser tools to help you see what's happening - it's extremely transparent.