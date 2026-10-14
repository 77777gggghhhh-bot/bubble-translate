package com.yourapp.translatebubble

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Temporary, developer-only screen: a running changelog of every real bug
 * we found and fixed while building this app, plus the handful of known
 * limitations that are platform constraints rather than something we can
 * currently fix. Meant to be deleted once development wraps up - it is
 * entirely self-contained (no dependency on any other screen or file) so
 * it is just as easy to remove later as it was to drop in now: delete
 * this file, remove its one <activity> line from AndroidManifest.xml, and
 * remove whichever button launches it.
 *
 * To wire it in:
 * 1) Add to AndroidManifest.xml, inside <application>:
 *      <activity android:name=".DevChangelogActivity" android:exported="false" />
 * 2) Launch it from anywhere (e.g. a button on your settings screen):
 *      startActivity(Intent(this, DevChangelogActivity::class.java))
 */
class DevChangelogActivity : AppCompatActivity() {

    private data class Entry(
        val severity: Severity,
        val title: String,
        val detail: String
    )

    private enum class Severity(val label: String, val color: String) {
        CRITICAL("حرج", "#FF5252"),
        MAJOR("مهم", "#FFB74D"),
        MINOR("بسيط", "#81C784"),
        FEATURE("إضافة", "#64B5F6"),
        LIMITATION("قيد تقني", "#9575CD")
    }

    private val entries = listOf(
        Entry(
            Severity.CRITICAL,
            "خطأ ترجمة: DOUBLE_TAP_MS غير معرّف",
            "منطق النقر المزدوج (نطق الترجمة صوتيًا) كان يستخدم ثابتًا ما تمت إضافته أبدًا، وهذا كان يمنع الـ build بالكامل. أُضيف الثابت داخل companion object."
        ),
        Entry(
            Severity.CRITICAL,
            "الـ OCR ما كان يشتغل أبدًا",
            "ملف accessibility_service_config.xml كان ناقص android:canTakeScreenshot=\"true\"، فكل محاولة قراءة نص من الصور والفيديوهات كانت تفشل فورًا (SecurityException) من أول يوم، بدون ما يظهر أي خطأ واضح للمستخدم. بعد الإصلاح، لازم المستخدم يعيد تفعيل صلاحية Accessibility يدويًا مرة وحدة لتفعيل الصلاحية الجديدة."
        ),
        Entry(
            Severity.MAJOR,
            "نص مكرر على الشاشة",
            "عقدة الأب وعقدة الابن في شجرة Accessibility كانتا أحيانًا تحملان نفس النص (مثلاً زر فيه TextView بنفس نص الزر)، فيُسجَّل كبلوكين منفصلين ويظهر نفس الجملة مترجمة مرتين فوق بعض. تم تجاهل أي نص يطابق نص الأب مباشرة بالأعلى."
        ),
        Entry(
            Severity.MAJOR,
            "دمج الجمل: تسلسل عشوائي عبر الشاشة",
            "أول نسخة من دمج البلوكات المتقاربة كانت تربط أي عنصرين قريبين من بعض بأي اتجاه، وعلى شاشة مزدحمة (اقتراحات + أزرار + شريط كتابة) كانت تتسلسل وتدمج عناصر غير مرتبطة إطلاقًا في كتلة واحدة مشوّشة. استُبدلت بمنطق اتجاهي: دمج فقط لنفس السطر أو نفس العمود (فقرة حقيقية)."
        ),
        Entry(
            Severity.MAJOR,
            "دمج الجمل: فقرات طويلة تنقطع بمنتصفها",
            "كحل مؤقت لمشكلة التسلسل، أُضيف حد أقصى لحجم الكتلة المدموجة كان صغيرًا جدًا (140dp)، فكانت الفقرات الطويلة (أكثر من بضعة أسطر) تنقسم لقطع منفصلة تُترجم بدون سياق كامل، وتطلع جملًا مفككة. رُفع الحد بعد ما استُبدل المنطق بالاتجاهي الجديد."
        ),
        Entry(
            Severity.MAJOR,
            "دمج الجمل: شبكة الأيقونات تلتهم الشاشة كلها",
            "تخطيط شبكي (مثل أيقونات الشاشة الرئيسية) يكون متصل أفقيًا وعموديًا بنفس الوقت، فحتى المنطق الاتجاهي كان يسلسل كل الشاشة في كتلة واحدة غير قابلة للقراءة. أُضيف حد أقصى لعدد البلوكات بالكتلة الواحدة (12) لمنع هذا تحديدًا."
        ),
        Entry(
            Severity.MAJOR,
            "شريط الحالة يُقرأ كنص",
            "لقطة الشاشة الخام تتضمن شريط الحالة (الساعة، البطارية، الشبكة)، وML Kit كان يحاول يقرأ أيقوناته كنص فينتج قمامة (مثل \"O24,0 KB/S LTED\") تختلط مع ترجمات حقيقية قريبة منها. صار يُقص شريط الحالة من الصورة قبل تمريرها للـ OCR."
        ),
        Entry(
            Severity.MAJOR,
            "صندوق الترجمة يقطع النص الطويل",
            "كان فيه حد أقصى بسطرين (maxLines = 2) على صندوق الترجمة، فأي ترجمة أطول من سطرين كانت تنقطع بدون تحذير، والباقي يبقى بالإنجليزي الأصلي ظاهر تحتها. أُزيل الحد."
        ),
        Entry(
            Severity.MAJOR,
            "صندوق الترجمة أصغر من النص الأصلي",
            "ما كان فيه حد أدنى لحجم الصندوق، بس حد أقصى فقط. لو الترجمة طلعت أقصر من النص الأصلي (شائع بالعربي)، الصندوق يصغّر نفسه ويسيب حواف من النص الأصلي بارزة. أُضيف حد أدنى يساوي حجم النص الأصلي بالضبط، فالتغطية تبقى كاملة بكل الحالات."
        ),
        Entry(
            Severity.MINOR,
            "عبارات واجهة ديناميكية تفلت من الفلترة",
            "عبارات مثل \"Go to [username]'s profile\" أو \"View [username]'s story\" ما كانت تتطابق مع قائمة العبارات الثابتة لأن فيها اسم مستخدم متغيّر بالمنتصف. أُضيفت أنماط (regex) للتعرف عليها كبنية عامة بدل نص حرفي."
        ),
        Entry(
            Severity.FEATURE,
            "فلترة كلمات الواجهة بناءً على الشكل، مو قائمة ثابتة",
            "بدل الاعتماد فقط على قائمة عبارات شائعة محفوظة يدويًا، صار فيه قاعدة عامة: كلمة وحيدة داخل صندوق بحجم أيقونة تُعتبر زر واجهة. هذا يعمم الفكرة على أي تطبيق أو لغة بدون ما يحتاج تحديث القائمة باستمرار."
        ),
        Entry(
            Severity.FEATURE,
            "اتجاه ترجمة واحد: غير العربي → عربي فقط",
            "الوضع التلقائي كان يترجم بالاتجاهين (عربي→إنجليزي وإنجليزي→عربي)، فكانت رسائل المستخدم العربية نفسها تترجم لإنجليزي غير مطلوب. صار النص العربي يُترك بدون لمس، نفس سلوك Google Lens بوضع \"إنجليزي → عربي\"."
        ),
        Entry(
            Severity.MINOR,
            "سجل الأخطاء (Last crash) ينمو للأبد",
            "دالة تسجيل اللوق كانت تضيف كل سطر فوق اللي قبله بدون أي حد، فـ\"Last crash\" كان يعرض أسابيع من السجل القديم قبل الوصول للمهم. صار محدود بآخر 80 سطر، ومع فاصل واضح يعلّم بداية كل جلسة تشغيل."
        ),
        Entry(
            Severity.MINOR,
            "إعادة ترجمة نفس النص من الصفر",
            "كل ضغطة على زر الترجمة كانت تنادي محرك الترجمة من جديد حتى لو نفس النص تُرجم قبل ثوانٍ على نفس الشاشة الثابتة. أُضيفت ذاكرة تخزين مؤقت (حتى 300 عبارة) تخلي الضغطة المكررة فورية."
        ),
        Entry(
            Severity.FEATURE,
            "حركة ظهور متدرجة (Staggered Reveal)",
            "صناديق الترجمة كانت تظهر فجأة دفعة وحدة. صارت تظهر بترتيب القراءة (فوق لتحت) بحركة pop متتابعة وسريعة، بتأثير بصري أقوى بكثير عند التسجيل."
        ),
        Entry(
            Severity.FEATURE,
            "تنبيه اكتشاف نص أجنبي تلقائيًا",
            "الفقاعة تنبض تلقائيًا لما تدخل شاشة فيها كمية كافية من نص غير عربي، بفحص خفيف جدًا (نص Accessibility بس، بدون screenshot) وبفترة تهدئة تمنع التكرار المزعج. مستوحاة من ميزة Live Translate الحصرية لأجهزة Pixel."
        ),
        Entry(
            Severity.LIMITATION,
            "قيد تقني: ML Kit ما يقرأ عربي من داخل الصور",
            "محرك الـ OCR المستخدم يدعم فقط: لاتيني، صيني، ياباني، كوري، وديفاناقري. نص عربي مرسوم داخل بكسلات صورة (meme مثلاً) ما يمكن قراءته بهذا المحرك إطلاقًا - هذا سقف بالمكتبة نفسها، مو خطأ بالكود. نص الواجهة (Accessibility) غير متأثر، يشتغل عربي عادي."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D12"))
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "سجل التطوير"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "كل خطأ اكتشفناه وصلحناه أثناء بناء التطبيق - شاشة مؤقتة لحد انتهاء التطوير"
            setTextColor(Color.parseColor("#9AA0AC"))
            textSize = 14f
            setPadding(0, dp(6), 0, dp(20))
        })

        entries.forEach { entry -> root.addView(buildCard(entry)) }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D12"))
            addView(root)
        }
        setContentView(scroll)
    }

    private fun buildCard(entry: Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, dp(10))
            layoutParams = lp
        }

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        badgeRow.addView(TextView(this).apply {
            text = entry.severity.label
            setTextColor(Color.parseColor(entry.severity.color))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
        })
        card.addView(badgeRow)

        card.addView(TextView(this).apply {
            text = entry.title
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(0, dp(4), 0, dp(6))
        })

        card.addView(TextView(this).apply {
            text = entry.detail
            setTextColor(Color.parseColor("#C7CAD1"))
            textSize = 13.5f
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        return card
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
