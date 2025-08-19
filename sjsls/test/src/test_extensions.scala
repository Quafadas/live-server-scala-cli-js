package io.github.quafadas.sjsls

extension (path: os.Path)
  def toFs2: fs2.io.file.Path =
    fs2.io.file.Path.fromNioPath(path.toNIO)
end extension
