package com.b3rnhard.sharedcomponents

public fun <TArg> (() -> Unit).withArg(): (TArg) -> Unit {
  return { unused: TArg -> this() }
}