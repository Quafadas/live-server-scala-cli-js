set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

projectName := "sjsls"

default:
  just --list

setupIde:
  mill mill.bsp.BSP/install

compile:
  mill {{projectName}}.compile

test:
  mill {{projectName}}.test.testOnly io.github.quafadas.sjsls.SafariSuite && mill {{projectName}}.test.testOnly io.github.quafadas.sjsls.RoutesSuite && mill {{projectName}}.test.testOnly io.github.quafadas.sjsls.UtilityFcs

checkOpts:
  mill {{projectName}}.run --help


jvmServe:
  mill -w {{projectName}}.runBackground --build-tool scala-cli --project-dir /Users/simon/Code/indigoLite --log-level info --browse-on-open-at / --path-to-index-html /Users/simon/Code/indigoLite/static

proxy:
  mill -w {{projectName}}.runBackground --project-dir /Users/simon/Code/viteless --port 3006 --proxy-prefix-path /api --proxy-target-port 8080 --log-level trace


goViteless:
  mill -w {{projectName}}.run --project-dir /Users/simon/Code/viteless --styles-dir /Users/simon/Code/viteless/styles

jvmServeNoStyles:
  mill {{projectName}}.run --build-tool scala-cli --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out --log-level trace

jvmLinker:
  mill {{projectName}}.run --build-tool scala-cli --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out --extra-build-args --js-cli-on-jvm --port 3007

serveMill:
  mill {{projectName}}.run --build-tool mill --project-dir /Users/simon/Code/mill-full-stack/mill-full-stack \
    --path-to-index-html /Users/simon/Code/mill-full-stack/mill-full-stack/frontend/ui \
    --out-dir /Users/simon/Code/mill-full-stack/mill-full-stack/out/frontend/fastLinkJS.dest \
    --log-level info \
    --port 3007 \
    --mill-module-name frontend \
    --proxy-prefix-path /api \
    --proxy-target-port 8080

setupPlaywright:
  cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps

publishLocal:
  mill __.publishLocal

setupMill:
  curl -L https://raw.githubusercontent.com/lefou/millw/0.4.11/millw > mill && chmod +x mill

format:
  mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

fix:
  mill __.fix

gha: setupMill setupPlaywright test
