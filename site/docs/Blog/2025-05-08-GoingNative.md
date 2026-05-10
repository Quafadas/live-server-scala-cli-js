---
2024-07-17 Going native
---
Seeing as typlevel can now compile to new scala native (and this is based on typelevel)... why not?

The coolest part of the journey was that it pretty much "just worked" in a scala sense. Getting a local build working was really fast.

## Roadbumps

- I hit one weird looking native-looking-memory-bug related I believe to `IO.blocking` instead of `IO.delay`. On my to do list to see if I can reproduc and if it's expected
- S2n and openssl. Took a little digging to realise [fs2 needs these](https://github.com/typelevel/fs2/pull/3721/changes)
- And getting it [all working in CI](https://github.com/Quafadas/live-server-scala-cli-js/blob/main/.github/workflows/release.yml) was hard work...I had to give up on a native windows build...
- Playwright on CI. OMG playwright on CI. I _think_ it's now stable


