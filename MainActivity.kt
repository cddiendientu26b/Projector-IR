package com.example.projectorir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- Mã IR (họ NEC) ----------
// ext=false: NEC chuẩn (addr 8 bit + ~addr). ext=true: NEC mở rộng (addr 16 bit).
data class Code(val a: Int, val ext: Boolean, val c: Int) {
    override fun toString() = (if (ext) "%04X" else "%02X").format(a) + " / " + "%02X".format(c)
}

fun pat(k: Code): IntArray {
    val a = if (k.ext) k.a and 0xFFFF else (k.a and 0xFF) or ((k.a.inv() and 0xFF) shl 8)
    val d = a.toLong() or ((k.c and 0xFF).toLong() shl 16) or ((k.c.inv() and 0xFF).toLong() shl 24)
    val p = ArrayList<Int>(70)
    p.add(9000); p.add(4500)
    for (i in 0 until 32) { p.add(560); p.add(if (((d shr i) and 1L) == 1L) 1690 else 560) }
    p.add(560); p.add(40000)
    return p.toIntArray()
}

suspend fun send(ir: ConsumerIrManager, k: Code) {
    val p = pat(k)
    ir.transmit(38000, p)
    delay(p.sum() / 1000L + 20)
}

fun rev(b: Int): Int {
    var r = 0
    for (i in 0..7) if (((b shr i) and 1) == 1) r = r or (1 shl (7 - i))
    return r
}

// Mã lấy từ tài liệu công khai nhưng chưa rõ thứ tự bit => thử cả 2 cách đọc
fun both(b0: Int, b1: Int, cmd: Int) = listOf(
    Code(b0 or (b1 shl 8), true, cmd),
    Code(rev(b0) or (rev(b1) shl 8), true, rev(cmd))
)

val CURATED: List<Code> =
    listOf(Code(0x4E87, true, 0x39), Code(0x4E87, true, 0x3A)) +   // InFocus tắt / bật
    both(0xCC, 0x00, 0x85) + both(0xCC, 0x00, 0x05) +              // Sanyo tắt / bật
    both(0x00, 0x0C, 0x40)                                          // BenQ nguồn

// Các lệnh hay dùng cho nút nguồn
val P = listOf(0x00, 0x01, 0x02, 0x03, 0x08, 0x0C, 0x10, 0x12, 0x15, 0x18, 0x40, 0x45, 0x46, 0x47, 0x48, 0x80)

// 0 = nhanh (mã có sẵn) | 1 = vừa (mọi địa chỉ NEC x lệnh hay dùng) | 2 = sâu (mọi địa chỉ x mọi lệnh)
fun candidates(level: Int): Sequence<Code> = sequence {
    yieldAll(CURATED)
    if (level >= 1) for (c in P) for (a in 0..255) yield(Code(a, false, c))
    if (level >= 2) for (c in 0..255) if (c !in P) for (a in 0..255) yield(Code(a, false, c))
}

// ---------- Lưu trữ mã đã học ----------
fun load(c: Context): MutableList<Code> =
    (c.getSharedPreferences("codes", 0).getString("list", "") ?: "")
        .split(";").filter { it.isNotBlank() }
        .map { val t = it.split(","); Code(t[0].toInt(), t[1] == "1", t[2].toInt()) }
        .toMutableList()

fun save(c: Context, l: List<Code>) {
    c.getSharedPreferences("codes", 0).edit()
        .putString("list", l.joinToString(";") { "${it.a},${if (it.ext) 1 else 0},${it.c}" }).apply()
}

// ---------- Giao diện ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        setContent { MaterialTheme { Surface { App(ir) } } }
    }
}

@Composable
fun App(ir: ConsumerIrManager?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r: ConsumerIrManager? = ir?.takeIf { it.hasIrEmitter() }
    val learned = remember { mutableStateListOf<Code>().apply { addAll(load(ctx)) } }
    val recent = remember { ArrayDeque<Code>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(1) }
    var status by remember { mutableStateOf("") }

    fun scan() {
        val x = r ?: return
        if (running) return
        job = scope.launch {
            running = true
            try {
                val list = candidates(level).toList()
                recent.clear()
                for ((i, k) in list.withIndex()) {
                    if (i % 8 == 0) status = "Đang quét ${i + 1}/${list.size}: $k"
                    send(x, k)
                    recent.addLast(k)
                    if (recent.size > 8) recent.removeFirst()
                }
                status = "Quét xong, chưa có mã nào được lưu"
            } finally { running = false }
        }
    }

    fun fire() {
        val x = r ?: return
        if (running) return
        job = scope.launch {
            running = true
            try {
                for (k in learned.toList()) {
                    status = "Đang gửi: $k"
                    send(x, k); delay(400); send(x, k); delay(400)   // bấm 2 lần cho máy cần xác nhận khi tắt
                }
                status = "Xong"
            } finally { running = false }
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        if (!running) Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = level == 0, onClick = { level = 0 }); Text("Nhanh", fontSize = 13.sp)
            RadioButton(selected = level == 1, onClick = { level = 1 }); Text("Vừa ~9ph", fontSize = 13.sp)
            RadioButton(selected = level == 2, onClick = { level = 2 }); Text("Sâu ~2h", fontSize = 13.sp)
        }
        Button(
            enabled = !running,
            modifier = Modifier.fillMaxWidth().height(110.dp),
            onClick = {
                if (r == null) { status = "Máy không có cổng IR" }
                else if (learned.isEmpty()) { scan() }
                else { fire() }
            }
        ) { Text(if (learned.isEmpty()) "BẬT / TẮT TẤT CẢ\n(quét mọi mã)" else "BẬT / TẮT TẤT CẢ", fontSize = 22.sp) }

        if (running) Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(
                modifier = Modifier.weight(1f).height(80.dp),
                onClick = {
                    job?.cancel()
                    val n = recent.toList()
                    n.forEach { if (it !in learned) learned.add(it) }
                    save(ctx, learned)
                    status = "Đã lưu ${n.size} mã gần nhất. Dùng Thử/Xóa để giữ lại mã đúng."
                }
            ) { Text("MÁY PHẢN ỨNG!\nLƯU MÃ") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(modifier = Modifier.height(80.dp), onClick = { job?.cancel(); status = "Đã dừng" }) { Text("DỪNG") }
        }

        Text(status, Modifier.padding(vertical = 8.dp))
        OutlinedButton(enabled = !running && r != null, onClick = { scan() }, modifier = Modifier.fillMaxWidth()) {
            Text("Quét tìm mã mới")
        }

        LazyColumn(Modifier.weight(1f)) {
            items(learned.size) { i ->
                val k = learned[i]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(k.toString(), Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { scope.launch { r?.let { send(it, k); delay(400); send(it, k) } } }) { Text("Thử") }
                    TextButton(onClick = { learned.remove(k); save(ctx, learned) }) { Text("Xóa") }
                }
            }
        }
    }
}
