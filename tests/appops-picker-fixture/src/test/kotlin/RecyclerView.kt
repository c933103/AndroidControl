// Display-only RecyclerView stand-in; these tests do not claim adapter/rendering coverage.
package androidx.recyclerview.widget
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
class LinearLayoutManager(context: Context)
class RecyclerView(context: Context) : LinearLayout(context) {
    var layoutManager: LinearLayoutManager? = null
    var adapter: Adapter<*>? = null
    open class ViewHolder(val itemView: View)
    abstract class Adapter<VH : ViewHolder> {
        abstract fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH
        abstract fun getItemCount(): Int
        abstract fun onBindViewHolder(holder: VH, position: Int)
        fun notifyDataSetChanged() { }
    }
}
