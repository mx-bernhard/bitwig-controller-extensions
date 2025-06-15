package com.b3rnhard.sharedcomponents

import com.bitwig.extension.callback.BooleanValueChangedCallback
import com.bitwig.extension.controller.api.BooleanValue
import com.bitwig.extension.controller.api.DocumentState
import com.bitwig.extension.controller.api.SettableEnumValue

interface ISettableBooleanValue : BooleanValue {
  fun set(value: Boolean)
  fun toggle()
}

fun DocumentState.getEnumBasedBooleanSetting(
  label: String?,
  category: String?,
  initialValue: Boolean,
  trueValue: String = "✓",
  falseValue: String = "×"
): ISettableBooleanValue {
  return SettableBooleanValue(this.getEnumSetting(label, category, arrayOf(trueValue, falseValue), if (initialValue) trueValue else falseValue), trueValue, falseValue)
}

class SettableBooleanValue(val internalSetting: SettableEnumValue, val trueValue: String, val falseValue: String) : ISettableBooleanValue {
  override fun set(value: Boolean) {
    return internalSetting.set(if (value) trueValue else falseValue)
  }

  override fun toggle() {
    return internalSetting.set(if (internalSetting.get() == trueValue) falseValue else trueValue)
  }

  override fun get(): Boolean {
    return internalSetting.get() == trueValue
  }

  override fun markInterested() {
    internalSetting.markInterested()
  }

  override fun addValueObserver(callback: BooleanValueChangedCallback) {
    internalSetting.addValueObserver({ callback.valueChanged(it == trueValue)})
  }

  override fun isSubscribed(): Boolean {
    return internalSetting.isSubscribed
  }

  override fun setIsSubscribed(value: Boolean) {
    internalSetting.setIsSubscribed(value)
  }

  override fun subscribe() {
    internalSetting.subscribe()
  }

  override fun unsubscribe() {
    internalSetting.unsubscribe()
  }
}