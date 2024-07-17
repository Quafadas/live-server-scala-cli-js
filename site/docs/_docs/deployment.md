---
title: Deployment
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





