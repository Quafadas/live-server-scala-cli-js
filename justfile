
default:
  just --list

setupIde:
  mill mill.bsp.BSP/install

compile:
  mill project.compile

test:
  mill project.test

jvmServe:
  scala-cli project.run /Users/simon/Code/helloScalaJs /Users/simon/Code/helloScalaJs/out

setupPlaywright:
  cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps

publish:
  mill

gha: setupPlaywright test
