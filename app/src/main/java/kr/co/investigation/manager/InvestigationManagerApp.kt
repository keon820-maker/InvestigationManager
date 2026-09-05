package kr.co.investigation.manager

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk
import kr.co.investigation.manager.sync.FirebaseBootstrap

class InvestigationManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        }
        FirebaseBootstrap.initialize(this)
    }
}
