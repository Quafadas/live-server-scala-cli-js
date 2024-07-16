set windows-shell := ["powershell.exe", "-NoLogo", "-Command"]

default:
  just --list

setupIde:
  mill mill.bsp.BSP/install

compile:
  mill project.compile

test:
  mill project.test.testOnly SafariSuite && mill project.test.testOnly RoutesSuite && mill project.test.testOnly UtilityFcs

checkOpts:
  mill project.run --help


jvmServe:
  mill -w project.runBackground --build-tool scala-cli --project-dir /Users/simon/Code/indigoLite --log-level info --browse-on-open-at / --path-to-index-html /Users/simon/Code/indigoLite/static

proxy:
  mill -w project.runBackground --project-dir /Users/simon/Code/viteless --port 3006 --proxy-prefix-path /api --proxy-target-port 8080 --log-level trace


goViteless:
  mill -w project.run --project-dir /Users/simon/Code/viteless --styles-dir /Users/simon/Code/viteless/styles

jvmServeNoStyles:
  mill project.run --build-tool scala-cli --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out --log-level trace

jvmLinker:
  mill project.run --build-tool scala-cli --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out --extra-build-args --js-cli-on-jvm --port 3007

serveMill:
  mill -j 0 project.run --build-tool mill --project-dir /Users/simon/Code/mill-full-stack/mill-full-stack \
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
  mill project.publishLocal

setupMill:
  curl -L https://raw.githubusercontent.com/lefou/millw/0.4.11/millw > mill && chmod +x mill

format:
  mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

fix:
  mill __.fix

gha: setupMill setupPlaywright test
