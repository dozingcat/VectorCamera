class RunningStats(val size: Int = 10) {
    val values = LongArray(size)
    var numValuesRecorded = 0
    var currentIndex = 0

    fun addValue(value: Long) {
        values[currentIndex] = value
        currentIndex = (currentIndex + 1) % size
        numValuesRecorded = minOf(numValuesRecorded + 1, size)
    }

    fun getAverage(): Double {
        if (numValuesRecorded == 0) {
            return Double.NaN
        }
        if (numValuesRecorded < size) {
            return values.take(numValuesRecorded).sum().toDouble() / numValuesRecorded
        }
        // android.util.Log.i("Stats", values.joinToString(" "))
        return values.sum().toDouble() / size        
    }

    fun clear() {
        numValuesRecorded = 0
        currentIndex = 0
    }
}
