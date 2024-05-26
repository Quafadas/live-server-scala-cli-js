extension (path: os.Path)
  def toFs2: fs2.io.file.Path =
    fs2.io.file.Path.fromNioPath(path.toNIO)
