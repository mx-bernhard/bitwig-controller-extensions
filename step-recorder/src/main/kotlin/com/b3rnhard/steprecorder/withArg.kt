package com.b3rnhard.steprecorder

public fun <TArg> (() -> Unit).withArg(): (TArg) -> Unit {
  return { unused: TArg -> this() }
}