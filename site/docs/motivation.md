# Motivation

I'm a big scala JS fan. However, the reliance on vite for the dev loop / deployment complexified my build piplines to the point where full stack wasn't fun for me anymore. I found maintaining _both_ a JVM setup and a node setup was annoying locally.

And then I had do it again in CI. So, intead of giving up on scala JS and going to write typescript, I figured it would be way more fun to simply try and go 100% scala - zero friction save, link, refresh.

I wanted to break the dependance on node / npm completely whilst retaining a sane developer experience for browser based scala-js development.