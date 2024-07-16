package io.github.quafadas.sjsls

import fs2.io.file.Path

enum IndexHtmlConfig:
  case IndexHtmlPath(path: Path)
  case StylesOnly(path: Path)
end IndexHtmlConfig
