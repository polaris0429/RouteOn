package com.example.routeon

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RegisterActivity : AppCompatActivity() {

    private var generatedCode = ""
    private lateinit var otpBoxes: Array<EditText>

    private var orgCode = ""
    private var companyName = ""
    private var username = ""
    private var password = ""
    private var isUsernameChecked = false

    private lateinit var cbAgreeAll: CheckBox

    private val silentSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val messageBody = sms.messageBody
                    if (messageBody.contains("[RouteOn]")) {
                        val code = Regex("\\d{6}").find(messageBody)?.value
                        if (code != null) {
                            for (i in 0 until 6) {
                                otpBoxes[i].setText(code[i].toString())
                            }
                            findViewById<Button>(R.id.btn_verify_sms).performClick()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
        }

        val layoutStep1 = findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = findViewById<LinearLayout>(R.id.layout_step2)
        val layoutStepTerms = findViewById<LinearLayout>(R.id.layout_step_terms)
        val layoutStep3 = findViewById<LinearLayout>(R.id.layout_step3)
        val layoutStep4 = findViewById<LinearLayout>(R.id.layout_step4)

        // Step 1
        val etOrgCode = findViewById<EditText>(R.id.et_org_code)
        val btnNextStep1 = findViewById<Button>(R.id.btn_next_step1)

        // Step 2
        val tvCompanyName = findViewById<TextView>(R.id.tv_company_name)
        val etName = findViewById<EditText>(R.id.et_name)
        val etUsername = findViewById<EditText>(R.id.et_username)
        val btnCheckUsername = findViewById<Button>(R.id.btn_check_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etPasswordConfirm = findViewById<EditText>(R.id.et_password_confirm)
        val btnNextStep2 = findViewById<Button>(R.id.btn_next_step2)

        // Step Terms
        cbAgreeAll = findViewById(R.id.cb_agree_all)
        val cbAgreeLocation = findViewById<CheckBox>(R.id.cb_agree_location)
        val cbAgreePrivacy = findViewById<CheckBox>(R.id.cb_agree_privacy)
        val cbAgreeMarketing = findViewById<CheckBox>(R.id.cb_agree_marketing)
        val btnNextStepTerms = findViewById<Button>(R.id.btn_next_step_terms)

        val svTermsLocation = findViewById<NestedScrollView>(R.id.sv_terms_location)
        val tvTermsLocation = findViewById<TextView>(R.id.tv_terms_location)
        val svTermsPrivacy = findViewById<NestedScrollView>(R.id.sv_terms_privacy)
        val tvTermsPrivacy = findViewById<TextView>(R.id.tv_terms_privacy)
        val svTermsMarketing = findViewById<NestedScrollView>(R.id.sv_terms_marketing)
        val tvTermsMarketing = findViewById<TextView>(R.id.tv_terms_marketing)
        val svTermsMain = findViewById<ScrollView>(R.id.sv_terms_main)

        // Step 3
        val etPhone = findViewById<EditText>(R.id.et_phone)
        val btnSendSms = findViewById<Button>(R.id.btn_send_sms)
        val layoutVerification = findViewById<LinearLayout>(R.id.layout_verification)
        val btnVerifySms = findViewById<Button>(R.id.btn_verify_sms)

        // Step 4
        val btnGoToLogin = findViewById<Button>(R.id.btn_go_to_login)

        otpBoxes = arrayOf(
            findViewById(R.id.otp1), findViewById(R.id.otp2), findViewById(R.id.otp3),
            findViewById(R.id.otp4), findViewById(R.id.otp5), findViewById(R.id.otp6)
        )
        setupOtpInputs()

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(silentSmsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(silentSmsReceiver, filter)
        }

        // ==========================================
        // 🚀 STEP 1 -> STEP 2
        // ==========================================
        btnNextStep1.setOnClickListener {
            orgCode = etOrgCode.text.toString().trim()
            if (orgCode.isEmpty()) {
                Toast.makeText(this, "조직코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnNextStep1.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("${Constants.BASE_URL}/organizations/lookup?org_code=$orgCode")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000

                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(responseData)
                        companyName = jsonResponse.optString("org_name", "조직")

                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            val welcomeText = "$companyName\n기사님 환영합니다!"
                            val spannable = SpannableString(welcomeText)
                            spannable.setSpan(RelativeSizeSpan(1.5f), 0, companyName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, companyName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

                            tvCompanyName.text = spannable
                            layoutStep1.visibility = View.GONE
                            layoutStep2.visibility = View.VISIBLE
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            Toast.makeText(this@RegisterActivity, "유효하지 않은 조직코드입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnNextStep1.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ==========================================
        // 🚀 아이디 중복확인
        // ==========================================
        btnCheckUsername.setOnClickListener {
            val inputId = etUsername.text.toString().trim()
            if (inputId.isEmpty()) {
                Toast.makeText(this, "아이디를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnCheckUsername.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("${Constants.BASE_URL}/auth/check-username?username=$inputId")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        if (responseCode == 200) {
                            isUsernameChecked = true
                            Toast.makeText(this@RegisterActivity, "사용 가능한 아이디입니다.", Toast.LENGTH_SHORT).show()
                            btnCheckUsername.text = "확인완료"
                            btnCheckUsername.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#03C75A"))
                        } else {
                            btnCheckUsername.isEnabled = true
                            Toast.makeText(this@RegisterActivity, "이미 사용 중인 아이디입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnCheckUsername.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "중복확인 서버 에러", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        etUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isUsernameChecked) {
                    isUsernameChecked = false
                    btnCheckUsername.isEnabled = true
                    btnCheckUsername.text = "중복확인"
                    btnCheckUsername.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ==========================================
        // 🚀 STEP 2 -> STEP TERMS
        // ==========================================
        btnNextStep2.setOnClickListener {
            val name = etName.text.toString().trim()
            username = etUsername.text.toString().trim()
            password = etPassword.text.toString().trim()
            val passwordConfirm = etPasswordConfirm.text.toString().trim()

            if (name.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isUsernameChecked) {
                Toast.makeText(this, "아이디 중복확인을 진행해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != passwordConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            layoutStep2.visibility = View.GONE
            layoutStepTerms.visibility = View.VISIBLE
        }

        // ==========================================
        // 🚀 STEP TERMS (약관 텍스트 세팅 및 끝까지 스크롤 시 해제 로직)
        // ==========================================
        tvTermsLocation.text = """
제1장 총 칙
제1조 (목적) 본 약관은 회원(루트온 위치기반서비스 약관에 동의한 자를 말합니다. 이하 “회원”이라고 합니다.)이 루트온(이하 “회사”라고 합니다.)이 제공하는 루트온 위치기반서비스(이하 “서비스”라고 합니다)를 이용함에 있어 회사와 회원의 권리·의무 및 책임사항을 규정함을 목적으로 합니다.
제2조 (이용약관의 효력 및 변경) ① 본 약관은 서비스를 신청한 고객 또는 개인위치정보주체가 본 약관에 동의하고 회사가 정한 소정의 절차에 따라 서비스의 이용자로 등록함으로써 효력이 발생합니다. ② 회원은 회사가 제시하는 방식(앱 내 동의, 온라인, 서면 등)으로 본 약관에 대한 사항을 확인하고 동의를 표명한 경우 본 약관의 내용을 모두 읽고 이를 충분히 이해하였으며, 그 적용에 동의한 것으로 봅니다. ③ 회사는 위치정보의 보호 및 이용 등에 관한 법률, 콘텐츠산업 진흥법, 전자상거래 등에서의 소비자보호에 관한 법률, 소비자기본법 약관의 규제에 관한 법률 등 관련법령을 위배하지 않는 범위에서 본 약관을 개정할 수 있습니다. ④ 회사가 약관을 개정할 경우에는 기존약관과 개정약관 및 개정약관의 적용일자와 개정사유를 명시하여 현행약관과 함께 그 적용일자 10일 전부터 적용일 이후 상당한 기간 동안 공지만을 하고, 개정 내용이 회원에게 불리한 경우에는 그 적용일자 30일 전부터 적용일 이후 상당한 기간 동안 각각 이를 서비스 앱 내 공지사항에 게시하거나 회원에게 전자적 형태(전자우편, SMS, 앱 푸시 등)로 약관 개정 사실을 발송하여 고지합니다. ⑤ 회사가 전항에 따라 회원에게 통지하면서 공지 또는 공지∙고지일로부터 개정약관 시행일 7일 후까지 거부의사를 표시하지 아니하면 이용약관에 승인한 것으로 봅니다. 회원이 개정약관에 동의하지 않을 경우 회원은 이용계약을 해지할 수 있습니다.
제3조 (관계법령의 적용) 본 약관은 신의성실의 원칙에 따라 공정하게 적용하며, 본 약관에 명시되지 아니한 사항에 대하여는 관계법령 또는 상관례에 따릅니다.
제4조 (서비스의 내용) 회사가 제공하는 서비스는 아래와 같습니다.
* 서비스 명: 루트온 위치기반서비스
* 서비스 내용:
    1. 현재 위치 기반 최적 배송 경로 안내 및 내비게이션 연동 서비스
    2. 실시간 배송 관제, GPS 트래킹 및 운행 기록 자동 수집 서비스
    3. 배송지(경유지) 접근 및 도착 알림 서비스
    4. 화주 및 관리자를 위한 차량 위치 공유 및 배차 관리 서비스
제5조 (서비스 이용요금) ① 회사가 제공하는 위치기반서비스는 기본적으로 무료입니다. 단, 회사가 제공하는 별도의 유료 서비스(기업용 관제 솔루션 등)의 경우 해당 서비스에 명시된 요금을 지불하여야 사용 가능합니다. ② 회사는 유료 서비스 이용요금을 회사와 계약한 전자지불업체에서 정한 방법에 의하거나 회사가 정한 청구서에 합산하여 청구할 수 있습니다. ③ 유료서비스 이용을 통하여 결제된 대금에 대한 취소 및 환불은 회사의 결제 이용약관 등 관계법에 따릅니다. ④ 회원의 개인정보도용 및 결제사기로 인한 환불요청 또는 결제자의 개인정보 요구는 법률이 정한 경우 외에는 거절될 수 있습니다. ⑤ 무선 서비스 이용 시 발생하는 데이터 통신료는 별도이며 가입한 각 이동통신사의 정책에 따릅니다.
제6조 (서비스내용변경 통지 등) ① 회사가 서비스 내용을 변경하거나 종료하는 경우 회사는 회원의 등록된 전자우편 주소 또는 앱 내 푸시 알림을 통하여 서비스 내용의 변경 사항 또는 종료를 통지할 수 있습니다. ② ①항의 경우 불특정 다수인을 상대로 통지를 함에 있어서는 웹사이트 또는 앱 내 공지사항을 통하여 회원들에게 통지할 수 있습니다.
제7조 (서비스이용의 제한 및 중지) ① 회사는 아래 각 호의 1에 해당하는 사유가 발생한 경우에는 회원의 서비스 이용을 제한하거나 중지시킬 수 있습니다.
1. 회원이 회사 서비스의 운영을 고의 또는 중과실로 방해하는 경우 
2. 서비스용 설비 점검, 보수 또는 공사로 인하여 부득이한 경우 
3. 전기통신사업법에 규정된 기간통신사업자가 전기통신 서비스를 중지했을 경우 
4. 국가비상사태, 서비스 설비의 장애 또는 서비스 이용의 폭주 등으로 서비스 이용에 지장이 있는 때 
5. 기타 중대한 사유로 인하여 회사가 서비스 제공을 지속하는 것이 부적당하다고 인정하는 경우 ② 회사는 전항의 규정에 의하여 서비스의 이용을 제한하거나 중지한 때에는 그 사유 및 제한기간 등을 회원에게 알려야 합니다. 
제8조 (개인위치정보의 이용 또는 제공) ① 회사는 개인위치정보를 이용하여 서비스를 제공하고자 하는 경우에는 다음 각호의 사항을 미리 이용약관에 명시한 후 개인위치정보주체의 동의를 얻어야 합니다.
1. 위치기반서비스사업자의 상호, 주소, 전화번호 그 밖의 연락처
2. 개인위치정보주체 및 법정대리인(이 약관 제10조, 제11조 규정에 의하여 법정대리인의 동의를 얻어야 하는 경우로 한정함)의 권리와 그 행사방법 
3. 위치기반서비스사업자가 제공하고자 하는 위치기반서비스의 내용 
4. 위치정보 이용ㆍ제공사실 확인자료의 보유근거 및 보유기간 
5. 본조 제4항에 규정된 통보에 관한 사항 ② 회사는 다음 각호의 목적으로 개인위치정보를 최대 1년간 보유∙이용할 수 있습니다. 이 경우 회사는 이 약관 및 관계 법령이 정하는 바에 따라 개인위치정보보호절차를 준수합니다. 
6. 이 약관 제4조에 따른 배송 경로 최적화 및 관제 등 위치기반서비스를 제공하기 위한 목적 
7. 회원의 배차 관리, 운행 기록 분석을 통한 서비스 품질 향상, 통계 자료 작성, 민원처리 및 요금정산, 운행 중 분쟁 예방 목적 ③ 회원 및 법정대리인의 권리와 그 행사방법은 제소 당시의 이용자의 주소에 의하며, 주소가 없는 경우에는 거소를 관할하는 지방법원의 전속관할로 합니다. 다만, 제소 당시 이용자의 주소 또는 거소가 분명하지 않거나 외국 거주자의 경우에는 민사소송법상의 관할법원에 제기합니다. ④ 회사는 개인위치정보를 회원이 지정하는 제3자(예: 소속 운수사 또는 화주)에게 제공하는 경우에는 개인위치정보를 수집한 당해 통신 단말장치로 매회 회원에게 제공받는 자, 제공일시 및 제공목적을 즉시 통보합니다. 단, 아래 각 호의 1에 해당하는 경우에는 회원이 미리 특정하여 지정한 통신 단말장치 또는 전자우편주소로 통보합니다.
8. 개인위치정보를 수집한 당해 통신단말장치가 문자, 음성 또는 영상의 수신기능을 갖추지 아니한 경우
9. 회원이 온라인 게시 등의 방법으로 통보할 것을 미리 요청한 경우 ⑤ 회사는 개인위치정보주체의 별도 동의가 있거나, 다음 각호에 해당하는 경우를 제외하고는 개인위치정보 또는 개인위치정보 수집∙이용∙제공사실 확인자료를 이 약관 제4조 및 본조 제2항에 명시 또는 고지한 범위를 넘어 보존∙이용하거나 제3자에게 제공하지 않습니다.
10. 타사업자 또는 회원과의 요금정산 및 민원처리를 위하여 위치정보 수집∙이용∙제공 사실 확인자료가 필요한 경우
11. 통계작성, 학술연구 또는 시장조사를 위하여 특정 개인을 알아볼 수 없는 형태로 가공하여 이용ㆍ제공하는 경우
제9조 (개인위치정보주체의 권리) ① 회원은 회사에 대하여 언제든지 개인위치정보를 이용한 위치기반서비스 제공 및 개인위치정보의 제3자 제공에 대한 동의의 전부 또는 일부를 철회할 수 있습니다. 이 경우 회사는 수집한 개인위치정보 및 위치정보 이용, 제공사실 확인자료를 파기합니다. ② 회원은 회사에 대하여 언제든지 개인위치정보의 수집, 이용 또는 제공의 일시적인 중지를 요구할 수 있으며, 회사는 이를 거절할 수 없고 이를 위한 기술적 수단을 갖추고 있습니다. (단, 운행 중 배차 관리를 위해 필수적인 경우 서비스 이용이 제한될 수 있습니다.) ③ 회원은 회사에 대하여 아래 각 호의 자료에 대한 열람 또는 고지를 요구할 수 있고, 당해 자료에 오류가 있는 경우에는 그 정정을 요구할 수 있습니다. 이 경우 회사는 정당한 사유 없이 회원의 요구를 거절할 수 없습니다.
1. 본인에 대한 위치정보 수집, 이용, 제공사실 확인자료
2. 본인의 개인위치정보가 위치정보의 보호 및 이용 등에 관한 법률 또는 다른 법률 규정에 의하여 제3자에게 제공된 이유 및 내용 ④ 회원은 제1항 내지 제3항의 권리행사를 위해 회사의 소정의 절차를 통해 요구할 수 있습니다.
제10조 (법정대리인의 권리) ① 회사는 14세 미만의 회원에 대해서는 개인위치정보를 이용한 위치기반서비스 제공 및 개인위치정보의 제3자 제공에 대한 동의를 당해 회원과 당해 회원의 법정대리인으로부터 동의를 받아야 합니다. 이 경우 법정대리인은 제9조에 의한 회원의 권리를 모두 가집니다. ② 회사는 14세 미만의 아동의 개인위치정보 또는 위치정보 이용․제공사실 확인자료를 이용약관에 명시 또는 고지한 범위를 넘어 이용하거나 제3자에게 제공하고자 하는 경우에는 14세 미만의 아동과 그 법정대리인의 동의를 받아야 합니다. 단, 아래의 경우는 제외합니다.
1. 위치정보 및 위치기반서비스 제공에 따른 요금정산을 위하여 위치정보 이용, 제공사실 확인자료가 필요한 경우 
2. 통계작성, 학술연구 또는 시장조사를 위하여 특정 개인을 알아볼 수 없는 형태로 가공하여 제공하는 경우 
제11조 (8세 이하의 아동 등의 보호의무자의 권리) ① 회사는 아래의 경우에 해당하는 자(이하 “8세 이하의 아동 등”이라 한다)의 보호의무자가 8세 이하의 아동 등의 생명 또는 신체보호를 위하여 개인위치정보의 이용 또는 제공에 동의하는 경우에는 본인의 동의가 있는 것으로 봅니다.
1. 8세 이하의 아동
2. 피성년후견인 (구 금치산자)
3. 장애인복지법 제2조 제2항 제2호의 규정에 의한 정신적 장애를 가진 자로서 장애인고용촉진 및 직업재활법 제2조 제2호의 규정에 의한 중증장애인에 해당하는 자(장애인복지법 제32조의 규정에 의하여 장애인등록을 한 자에 한한다) ② 8세 이하의 아동 등의 생명 또는 신체의 보호를 위하여 개인위치정보의 이용 또는 제공에 동의를 하고자 하는 보호의무자는 서면동의서에 보호의무자임을 증명하는 서면을 첨부하여 회사에 제출하여야 합니다. ③ 보호의무자는 8세 이하의 아동 등의 개인위치정보 이용 또는 제공에 동의하는 경우 개인위치정보주체 권리의 전부를 행사할 수 있습니다.
제12조 (개인위치정보의 보존 및 파기) ① 회사는 ‘위치정보의 보호 및 이용 등에 관한 법률’ 제16조 제2항에 근거하여 타사업자 또는 회원과의 요금정산 및 민원처리를 위하여 위치정보 수집사실 확인 자료 및 위치정보 이용 ∙제공 사실 확인자료를 자동으로 기록하여 12개월간 보존합니다. 단, 관계 법령에 따라 보존할 의무 및 필요성이 있는 경우에는 그에 따라 보존합니다. ② 이 약관 제4조 및 제8조 제2항의 목적을 달성한 때에 개인위치정보를 즉시 파기합니다.
제13조 (위치정보관리책임자의 지정) ① 회사는 위치정보를 적절히 관리․보호하고 개인위치정보주체의 불만을 원활히 처리할 수 있도록 실질적인 책임을 질 수 있는 지위에 있는 자를 위치정보관리책임자로 지정해 운영합니다. ② 위치정보관리책임자는 위치기반서비스를 제공하는 부서의 부서장으로서 구체적인 사항은 본 약관의 부칙에 따릅니다.
제14조 (손해배상) ① 회사가 위치정보의 보호 및 이용 등에 관한 법률 제15조 내지 제26조의 규정을 위반한 행위로 회원에게 손해가 발생한 경우 회원은 회사에 대하여 손해배상 청구를 할 수 있습니다. 이 경우 회사는 고의, 과실이 없음을 입증하지 못하는 경우 책임을 면할 수 없습니다. ② 회원이 본 약관의 규정을 위반하여 회사에 손해가 발생한 경우 회사는 회원에 대하여 손해배상을 청구할 수 있습니다. 이 경우 회원은 고의, 과실이 없음을 입증하지 못하는 경우 책임을 면할 수 없습니다.
제15조 (면책) ① 회사는 다음 각 호의 경우로 서비스를 제공할 수 없는 경우 이로 인하여 회원에게 발생한 손해에 대해서는 책임을 부담하지 않습니다.
1. 천재지변 또는 이에 준하는 불가항력의 상태가 있는 경우 
2. 서비스 제공을 위하여 회사와 서비스 제휴계약을 체결한 제3자의 고의적인 서비스 방해가 있는 경우 
3. 회원의 귀책사유로 서비스 이용에 장애가 있는 경우 
4. 제1호 내지 제3호를 제외한 기타 회사의 고의∙과실이 없는 사유로 인한 경우 ② 회사는 서비스 및 서비스에 게재된 정보, 자료, 사실의 신뢰도, 정확성 등에 대해서는 보증을 하지 않으며 이로 인해 발생한 회원의 손해에 대하여는 책임을 부담하지 아니합니다. 
제16조 (규정의 준용) ① 본 약관은 대한민국법령에 의하여 규정되고 이행됩니다. ② 본 약관에 규정되지 않은 사항에 대해서는 관련법령 및 상관습에 의합니다.
제17조 (분쟁의조정 및 기타) ① 회사는 위치정보와 관련된 분쟁에 대해 당사자간 협의가 이루어지지 아니하거나 협의를 할 수 없는 경우에는 위치정보의 보호 및 이용 등에 관한 법률 제28조의 규정에 의한 방송통신위원회에 재정을 신청할 수 있습니다. ② 회사 또는 고객은 위치정보와 관련된 분쟁에 대해 당사자간 협의가 이루어지지 아니하거나 협의를 할 수 없는 경우에는 개인정보보호법 제43조의 규정에 의한 개인정보분쟁조정위원회에 조정을 신청할 수 있습니다.
제18조 (회사의 연락처) 회사의 상호 및 주소 등은 다음과 같습니다.
1. 상 호 : 루트온
2. 주 소 : 경기도 양주시 고암동 청담로 95
3. 대표전화 : 010-5702-2581
부칙 제1조 (시행일) 본 약관은 2026년 5월 6일부터 시행합니다. 제2조 위치정보관리책임자는 다음과 같이 지정합니다.
1. 소속: 루트온 개발운영팀
2. 연락처: 010-5702-2581
3. 담당자: 신우철
        """.trimIndent()

        tvTermsPrivacy.text = """
개인정보 수집·이용 동의

[수집 항목]
회사는 서비스 제공을 위해 아래와 같은 개인정보를 수집합니다.

1. 필수 항목
- 아이디, 비밀번호
- 전화번호
- 위치정보 (GPS)
- 기기정보 (OS, 앱 버전, 디바이스 식별 정보 등)

2. 자동 수집 항목
- 서비스 이용 기록
- 접속 로그, IP 주소
- 쿠키 및 접속 환경 정보

[수집 및 이용 목적]
회사는 수집한 개인정보를 다음의 목적을 위해 이용합니다.

1. 회원가입 및 본인 확인
2. 위치 기반 배송 경로 안내 및 관제 서비스 제공
3. 실시간 위치 추적 및 운행 기록 관리
4. 고객 문의 대응 및 민원 처리
5. 서비스 개선 및 통계 분석

[보유 및 이용 기간]
회사는 개인정보 수집 및 이용 목적이 달성된 후에는 해당 정보를 지체 없이 파기합니다.
단, 관계 법령에 따라 보존할 필요가 있는 경우 해당 법령에서 정한 기간 동안 보관합니다.

[동의 거부 권리]
이용자는 개인정보 수집 및 이용에 대한 동의를 거부할 권리가 있습니다.
단, 필수 항목에 대한 동의를 거부할 경우 서비스 이용이 제한될 수 있습니다.
        """.trimIndent()

        tvTermsMarketing.text = """
마케팅 정보 수신 동의 (선택)

회사는 서비스 관련 이벤트, 혜택, 프로모션 정보를 제공하기 위해 아래와 같이 개인정보를 이용할 수 있습니다.

[수집 항목]
- 전화번호

[이용 목적]
- 이벤트 및 프로모션 안내
- 서비스 관련 혜택 및 광고 정보 제공

[수신 방법]
- SMS, 앱 푸시 알림

[보유 및 이용 기간]
- 동의 철회 시까지

※ 이용자는 본 동의를 거부할 수 있으며, 거부하더라도 서비스 이용에는 제한이 없습니다.
        """.trimIndent()

        fun checkAllTermsScrolled() {
            if (cbAgreeLocation.isEnabled && cbAgreePrivacy.isEnabled && cbAgreeMarketing.isEnabled) {
                cbAgreeAll.isEnabled = true
            }
        }

        setupTermScrollBehavior(svTermsLocation, cbAgreeLocation) { checkAllTermsScrolled() }
        setupTermScrollBehavior(svTermsPrivacy, cbAgreePrivacy) { checkAllTermsScrolled() }
        setupTermScrollBehavior(svTermsMarketing, cbAgreeMarketing) { checkAllTermsScrolled() }

        // 화면 밖 스크롤뷰(ScrollView)가 NestedScrollView의 스크롤을 가로채는 현상 방지
        svTermsMain.setOnTouchListener { _, _ -> false }

        val termsCheckListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            val isLocChecked = cbAgreeLocation.isChecked
            val isPrivChecked = cbAgreePrivacy.isChecked
            val isMarkChecked = cbAgreeMarketing.isChecked

            val isNextEnabled = isLocChecked && isPrivChecked

            cbAgreeAll.setOnCheckedChangeListener(null)
            cbAgreeAll.isChecked = isLocChecked && isPrivChecked && isMarkChecked

            cbAgreeAll.setOnCheckedChangeListener { _, isChecked ->
                if (cbAgreeLocation.isEnabled) cbAgreeLocation.isChecked = isChecked
                if (cbAgreePrivacy.isEnabled) cbAgreePrivacy.isChecked = isChecked
                if (cbAgreeMarketing.isEnabled) cbAgreeMarketing.isChecked = isChecked

                val nextEnabled = cbAgreeLocation.isChecked && cbAgreePrivacy.isChecked
                btnNextStepTerms.isEnabled = nextEnabled
                btnNextStepTerms.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (nextEnabled) "#FEE500" else "#CCCCCC"))
            }

            btnNextStepTerms.isEnabled = isNextEnabled
            btnNextStepTerms.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (isNextEnabled) "#FEE500" else "#CCCCCC"))
        }

        cbAgreeAll.setOnCheckedChangeListener { _, isChecked ->
            if (cbAgreeLocation.isEnabled) cbAgreeLocation.isChecked = isChecked
            if (cbAgreePrivacy.isEnabled) cbAgreePrivacy.isChecked = isChecked
            if (cbAgreeMarketing.isEnabled) cbAgreeMarketing.isChecked = isChecked

            val isNextEnabled = cbAgreeLocation.isChecked && cbAgreePrivacy.isChecked
            btnNextStepTerms.isEnabled = isNextEnabled
            btnNextStepTerms.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (isNextEnabled) "#FEE500" else "#CCCCCC"))
        }

        cbAgreeLocation.setOnCheckedChangeListener(termsCheckListener)
        cbAgreePrivacy.setOnCheckedChangeListener(termsCheckListener)
        cbAgreeMarketing.setOnCheckedChangeListener(termsCheckListener)

        btnNextStepTerms.setOnClickListener {
            layoutStepTerms.visibility = View.GONE
            layoutStep3.visibility = View.VISIBLE
        }

        // ==========================================
        // 🚀 STEP 3: SMS 인증 후 최종 가입
        // ==========================================
        btnSendSms.setOnClickListener {
            val phone = etPhone.text.toString().trim().replace("-", "")
            if (phone.isEmpty()) {
                Toast.makeText(this, "휴대전화 번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            generatedCode = (100000..999999).random().toString()
            btnSendSms.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val apiKey = BuildConfig.SOLAPI_API_KEY
                    val apiSecret = BuildConfig.SOLAPI_API_SECRET

                    val salt = UUID.randomUUID().toString().replace("-", "")
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val date = sdf.format(Date())

                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
                    val signature = mac.doFinal((date + salt).toByteArray()).joinToString("") { String.format("%02x", (it.toInt() and 0xFF)) }
                    val authHeader = "HMAC-SHA256 apiKey=$apiKey, date=$date, salt=$salt, signature=$signature"

                    val url = URL("https://api.solapi.com/messages/v4/send")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", authHeader)
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    val messageObj = JSONObject().apply {
                        put("to", phone)
                        put("from", "01057022581")
                        put("text", "[RouteOn] 기사 가입 인증번호는 [$generatedCode] 입니다.")
                    }
                    val jsonParam = JSONObject().apply { put("message", messageObj) }

                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        btnSendSms.isEnabled = true
                        if (responseCode == 200) {
                            layoutVerification.visibility = View.VISIBLE
                            otpBoxes[0].requestFocus()
                            Toast.makeText(this@RegisterActivity, "인증번호 발송 완료!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@RegisterActivity, "발송 실패 (코드: $responseCode)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnSendSms.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "문자 발송 네트워크 에러", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnVerifySms.setOnClickListener {
            val inputCode = otpBoxes.joinToString("") { it.text.toString() }
            val phone = etPhone.text.toString().trim()

            if (inputCode == generatedCode && inputCode.length == 6) {
                btnVerifySms.isEnabled = false
                registerUserOnServer(username, password, phone, orgCode)
            } else {
                Toast.makeText(this, "인증번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // ==========================================
        // 🚀 STEP 4: 가입 완료 화면 -> 로그인 이동
        // ==========================================
        btnGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun registerUserOnServer(usernameStr: String, passwordStr: String, phoneStr: String, orgCodeStr: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("${Constants.BASE_URL}/auth/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("username", usernameStr)
                    put("password", passwordStr)
                    put("phone", phoneStr)
                    put("org_code", orgCodeStr)
                    put("role", "driver")
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                val responseCode = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 201 || responseCode == 200) {
                        findViewById<LinearLayout>(R.id.layout_step3).visibility = View.GONE
                        findViewById<LinearLayout>(R.id.layout_step4).visibility = View.VISIBLE
                    } else {
                        findViewById<Button>(R.id.btn_verify_sms).isEnabled = true
                        Toast.makeText(this@RegisterActivity, "가입 실패. (조직코드를 다시 확인해주세요)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<Button>(R.id.btn_verify_sms).isEnabled = true
                    Toast.makeText(this@RegisterActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupOtpInputs() {
        for (i in otpBoxes.indices) {
            otpBoxes[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < 5) otpBoxes[i + 1].requestFocus()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            otpBoxes[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (otpBoxes[i].text.isEmpty() && i > 0) {
                        otpBoxes[i - 1].requestFocus()
                        otpBoxes[i - 1].text.clear()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTermScrollBehavior(scrollView: NestedScrollView, checkBox: CheckBox, onScrollEnd: () -> Unit) {
        scrollView.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        scrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, _, _, _ ->
            if (!v.canScrollVertically(1)) {
                checkBox.isEnabled = true
                onScrollEnd()
            }
        })

        scrollView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scrollView.measuredHeight > 0) {
                    scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val child = scrollView.getChildAt(0)
                    if (child != null && child.measuredHeight <= scrollView.measuredHeight) {
                        checkBox.isEnabled = true
                        onScrollEnd()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(silentSmsReceiver) } catch (_: Exception) {}
    }
}