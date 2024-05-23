
default:
  just --list

setupIde:
  mill mill.bsp.BSP/install

compile:
  mill project.compile

test:
  mill project.test

jvmServe:
  mill project.run --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out --styles-dir /Users/simon/Code/helloScalaJs/styles

jvmServeNoStyles:
  mill project.run --project-dir /Users/simon/Code/helloScalaJs --out-dir /Users/simon/Code/helloScalaJs/out

setupPlaywright:
  cs launch com.microsoft.playwright:playwright:1.41.1 -M "com.microsoft.playwright.CLI" -- install --with-deps

publish:
  mill

setupMill:
  curl -L https://raw.githubusercontent.com/lefou/millw/0.4.11/millw > mill && chmod +x mill

gha: setupMill setupPlaywright test
