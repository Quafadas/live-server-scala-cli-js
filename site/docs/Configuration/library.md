# Library

The LiveServerConfig can also accept an `fs2.Topic` as a parameter. This allows any tool which can instantiate an `fs2.Topic` to emit a pulse, which will refresh the client. Have a look at the mill plugin code for details.