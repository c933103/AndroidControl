package androidx.coordinatorlayout.widget
class CoordinatorLayout {
    class LayoutParams(width: Int, height: Int) : android.view.ViewGroup.LayoutParams(width, height) {
        var behavior: Any? = null
    }
}
