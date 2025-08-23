Ummm... no Bundler?
---

Yup, no bundler.

Instead, consider using [ESModules](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Modules), and resolving NPM dependancies out of a [CDN](https://www.jsdelivr.com).

It's less scary than it appears, and it feels _very_ good once it's up and going. An example that uses shoelace. [Sans Bundler](https://github.com/Quafadas/ShoelaceSansBundler).

# Scala-CLI

You'll need an `importmap.json` file.

```json
{
  "imports": {
    "@shoelace-style/shoelace/dist/": "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.13.1/cdn/",
  }
}
```

and directives that tells scala-cli to use scala js, esmodules and where to find it.

```
//> using platform js
//> using jsModuleKind es
//> using jsEsModuleImportMap importmap.json
//> using jsModuleSplitStyleStr smallmodulesfor
//> using jsSmallModuleForPackage frontend
//> using dep com.raquo::laminar-shoelace::0.1.0
```

# Mill (0.11.8+)

In your frontend module

```scala sc:nocompile

  override def scalaJSImportMap = T {
    Seq(
      ESModuleImportMapping.Prefix("@shoelace-style/shoelace/dist/", "https://cdn.jsdelivr.net/npm/@shoelace-style/shoelace@2.13.1/cdn/")
    )
  }
```

Don't forget to depend on the facade too :-)...

```scala sc:nocompile
def ivyDeps = Agg( ivy"com.raquo::laminar-shoelace::0.1.0" )
```

# SBT

I haven't personally used, but it would be possible, with this plugin;

https://github.com/armanbilge/scalajs-importmap

# Misc

If you're walking on the bleeding edge with modules that aren't designed to be loaded out of a CDN (looking at you, SAP UI5 webcomponents), then things are not easy. You may need to give the browser some hints, on where it can resolve other parts of the module grap in your index.html;


```json
<script type="importmap">
  {
    "imports": {
      "@ui5/webcomponents-theming/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents-theming/",
      "@ui5/webcomponents-localization/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents-localization@1.24.7/",
      "@ui5/webcomponents/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents@1.24.7/",
      "@ui5/webcomponents-theming/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents-theming@1.24.7/",
      "@ui5/webcomponents-icons/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents-icons@1.24.7/",
      "@ui5/webcomponents-base/": "https://cdn.jsdelivr.net/npm/@ui5/webcomponents-base@1.24.7/",
      "@sap-theming/": "https://cdn.jsdelivr.net/npm/@sap-theming@1.24.7/",
      "@types/openui5/": "https://cdn.jsdelivr.net/npm/@types/openui5@1.24.7/",
      "@types/jquery/": "https://cdn.jsdelivr.net/npm/@types/jquery/",
      "lit-html": "https://cdn.jsdelivr.net/npm/lit-html",
      "lit-html/": "https://cdn.jsdelivr.net/npm/lit-html/"
    }
  }
```
This actually got 95% of the way there... but localisation doesn't work. That means (for example) the date picker doesn't work. I have no good answer for this. Given that project explicitly recommends not using a CDN, it's not an obvious move to attempt this.

The browser network tools are your friend.