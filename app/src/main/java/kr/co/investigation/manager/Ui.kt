@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.co.investigation.manager.archive.ArchiveService
import kr.co.investigation.manager.data.*
import kr.co.investigation.manager.ocr.OcrService
import kr.co.investigation.manager.pdf.RequestPdf
import kr.co.investigation.manager.storage.OriginalFileStore
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable fun InvestigationApp(vm:AppViewModel){
    var screen by remember{mutableStateOf("main")}
    var formReturn by remember{mutableStateOf("main")}
    var viewingAttachment by remember{mutableStateOf<Attachment?>(null)}
    when(screen){
        "main"->MainScreen(
            vm,
            onNew={screen="ocr"},
            onEdit={vm.select(it);screen="detail"},
            onForm={vm.select(it);formReturn="main";screen="form"},
            onSettings={screen="settings"}
        )
        "ocr"->OcrRegisterScreen(vm,onDone={screen="main"},onCancel={screen="main"})
        "detail"->vm.selected.collectAsStateWithLifecycle().value?.let{
            DetailScreen(
                vm=vm,
                c0=it,
                onBack={screen="main"},
                onForm={formReturn="detail";screen="form"},
                onAttachment={att->viewingAttachment=att;screen="attachment"}
            )
        }
        "form"->vm.selected.collectAsStateWithLifecycle().value?.let{
            RequestFormScreen(it,onBack={screen=formReturn})
        }
        "attachment"->viewingAttachment?.let{
            AttachmentViewerScreen(it,onBack={screen="detail"})
        }
        "settings"->SettingsScreen(vm,onBack={screen="main"})
    }
}

@Composable fun MainScreen(
    vm:AppViewModel,
    onNew:()->Unit,
    onEdit:(InvestigationCase)->Unit,
    onForm:(InvestigationCase)->Unit,
    onSettings:()->Unit
){
    val cases by vm.cases.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var q by remember{mutableStateOf("")}
    var mobileTab by remember{mutableIntStateOf(0)}
    val filtered=remember(cases,q){cases.filter{q.isBlank()|| listOf(it.managementNo,it.debtorName,it.propertyAddress,it.phone,it.mobile).any{s->s.contains(q,true)}}}
    Scaffold(topBar={TopAppBar(title={Text("조사관리")},actions={TextButton(onClick={vm.setYear(year-1)}){Text("‹")};Text("$year");TextButton(onClick={vm.setYear(year+1)}){Text("›")};TextButton(onClick=onSettings){Text("데이터")};Button(onClick=onNew){Text("+ 신규등록")}})}){pad->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()){
            val wide=maxWidth>=600.dp
            val list:@Composable (Modifier)->Unit={m->
                CaseList(
                    items=filtered,
                    q=q,
                    onQ={q=it},
                    onSelect={vm.select(it)},
                    onEdit=onEdit,
                    onForm=onForm,
                    onComplete={vm.update(it.copy(status="완료"))},
                    modifier=m
                )
            }
            if(wide) Row(Modifier.fillMaxSize()){
                list(Modifier.weight(.44f))
                NativeMapPane(filtered,selected,Modifier.weight(.56f))
            } else Column(Modifier.fillMaxSize()){
                TabRow(mobileTab){
                    Tab(mobileTab==0,{mobileTab=0},text={Text("목록")})
                    Tab(mobileTab==1,{mobileTab=1},text={Text("지도")})
                }
                if(mobileTab==0) list(Modifier.fillMaxSize()) else NativeMapPane(filtered,selected,Modifier.fillMaxSize())
            }
        }
    }
}

@Composable fun CaseList(
    items:List<InvestigationCase>,
    q:String,
    onQ:(String)->Unit,
    onSelect:(InvestigationCase)->Unit,
    onEdit:(InvestigationCase)->Unit,
    onForm:(InvestigationCase)->Unit,
    onComplete:(InvestigationCase)->Unit,
    modifier:Modifier
){
    var menuCaseId by remember{mutableStateOf<Long?>(null)}
    Column(modifier.padding(12.dp)){
        OutlinedTextField(q,onQ,label={Text("관리번호·채무자·주소·전화번호 검색")},modifier=Modifier.fillMaxWidth())
        Text("리스트를 누르면 작업 메뉴가 열립니다.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=5.dp,bottom=3.dp))
        LazyColumn{
            items(items,key={it.id}){c->
                Box(Modifier.fillMaxWidth()){
                    Card(
                        Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{
                            onSelect(c)
                            menuCaseId=c.id
                        }
                    ){
                        Column(Modifier.padding(12.dp)){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                Text(c.managementNo.ifBlank{"관리번호 없음"},style=MaterialTheme.typography.titleMedium)
                                AssistChip(onClick={},label={Text(c.status)})
                            }
                            Text(c.debtorName)
                            Text(c.propertyAddress,maxLines=2)
                            Text("완료요청 ${c.dueDate}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    DropdownMenu(
                        expanded=menuCaseId==c.id,
                        onDismissRequest={menuCaseId=null}
                    ){
                        DropdownMenuItem(
                            text={Text("편집")},
                            onClick={menuCaseId=null;onEdit(c)}
                        )
                        DropdownMenuItem(
                            text={Text("조사의뢰서")},
                            onClick={menuCaseId=null;onForm(c)}
                        )
                        DropdownMenuItem(
                            text={Text(if(c.status=="완료") "완료처리됨" else "완료처리")},
                            enabled=c.status!="완료",
                            onClick={menuCaseId=null;onComplete(c)}
                        )
                    }
                }
            }
        }
    }
}

@Composable fun OcrRegisterScreen(vm:AppViewModel,onDone:()->Unit,onCancel:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var raw by remember{mutableStateOf("")}
    var showRaw by remember{mutableStateOf(false)}
    var parsed by remember{mutableStateOf(InvestigationCase(year=LocalDate.now().year))}
    var busy by remember{mutableStateOf(false)}
    var source by remember{mutableStateOf<Uri?>(null)}
    var preprocess by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u->
        if(u!=null){
            source=u
            busy=true
            showRaw=false
            raw=""
            preprocess="문서 분석 중..."
            scope.launch{
                runCatching{OcrService.recognizeCase(ctx,u)}
                    .onSuccess{r->raw=r.rawText;parsed=r.parsed;preprocess=r.preprocessMessage}
                    .onFailure{preprocess="OCR 실패: ${it.message.orEmpty()}"}
                busy=false
            }
        }
    }
    Scaffold(topBar={TopAppBar(title={Text("OCR 조사의뢰서 등록")},navigationIcon={TextButton(onClick=onCancel){Text("뒤로")}})}){pad->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)){
            Button(onClick={picker.launch("image/*")}){Text("조사의뢰서 사진 선택")}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(preprocess.isNotBlank()) Text(preprocess,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
            if(source!=null&&!busy){
                Text("자동인식 결과",style=MaterialTheme.typography.titleMedium)
                Text("아래 항목을 확인·수정한 뒤 저장하세요. OCR 원문은 기본적으로 숨깁니다.",style=MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            EditFields(parsed){parsed=it}
            Spacer(Modifier.height(12.dp))
            Row{
                Button(enabled=source!=null&&!busy,onClick={scope.launch{
                    val id=vm.create(parsed)
                    source?.let{
                        val a=OriginalFileStore.copyOriginal(ctx,it,id,parsed.year,"ORIGINAL_REQUEST").attachment
                        vm.addAttachment(a)
                    }
                    onDone()
                }}){Text("검수 완료 및 저장")}
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled=raw.isNotBlank(),onClick={showRaw=!showRaw}){Text(if(showRaw)"OCR 원문 숨기기" else "OCR 원문 보기")}
            }
            if(showRaw&&raw.isNotBlank()){
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Text("OCR 원문(진단용)",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=10.dp))
                Text(raw,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable fun EditFields(c:InvestigationCase,on:(InvestigationCase)->Unit){
    @Composable fun f(label:String,v:String,set:(String)->InvestigationCase){
        OutlinedTextField(v,{on(set(it))},label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=3.dp))
    }
    f("관리번호",c.managementNo){c.copy(managementNo=it)}
    f("의뢰일",c.requestDate){c.copy(requestDate=it)}
    f("조사담당자",c.investigator){c.copy(investigator=it)}
    f("조사담당자 전화",c.investigatorPhone){c.copy(investigatorPhone=it)}
    f("조사담당자 Fax",c.investigatorFax){c.copy(investigatorFax=it)}
    f("채무자명",c.debtorName){c.copy(debtorName=it)}
    f("전화번호",c.phone){c.copy(phone=it)}
    f("핸드폰번호",c.mobile){c.copy(mobile=it)}
    f("완료요청일",c.dueDate){c.copy(dueDate=it)}
    f("조사구분",c.investigationType){c.copy(investigationType=it)}
    f("대출종류",c.loanType){c.copy(loanType=it)}
    f("물건종류",c.propertyType){c.copy(propertyType=it)}
    f("물건소재지 (지도 기준)",c.propertyAddress){c.copy(propertyAddress=it)}
    f("물건소유자",c.ownerName){c.copy(ownerName=it)}
    f("주민번호",c.ownerResidentNo){c.copy(ownerResidentNo=it)}
    f("소유자 연락처",c.ownerPhone){c.copy(ownerPhone=it)}
    f("소유자 주소",c.ownerAddress){c.copy(ownerAddress=it)}
    f("기타요청사항",c.requestNotes){c.copy(requestNotes=it)}
    f("영업점",c.branch){c.copy(branch=it)}
    f("영업점 전화",c.branchPhone){c.copy(branchPhone=it)}
    f("영업점 Fax",c.branchFax){c.copy(branchFax=it)}
    f("조사의뢰자",c.requester){c.copy(requester=it)}
}

@Composable fun DetailScreen(
    vm:AppViewModel,
    c0:InvestigationCase,
    onBack:()->Unit,
    onForm:()->Unit,
    onAttachment:(Attachment)->Unit
){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var c by remember(c0){mutableStateOf(c0)}
    val atts by vm.db.attachments().observe(c.id).collectAsStateWithLifecycle(emptyList())
    var pending by remember{mutableStateOf<File?>(null)}
    var confirmDelete by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u->if(u!=null)scope.launch{vm.addAttachment(OriginalFileStore.copyOriginal(ctx,u,c.id,c.year,"CONFIRMATION").attachment)}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        val f=pending
        if(ok&&f!=null) scope.launch{vm.addAttachment(OriginalFileStore.finalizeCamera(f,c.id,"CONFIRMATION").attachment)}
    }
    if(confirmDelete) AlertDialog(
        onDismissRequest={confirmDelete=false},
        title={Text("조사건 삭제")},
        text={Text("${c.managementNo.ifBlank{"이 조사건"}}을 휴지통으로 이동하시겠습니까?\n연결된 원본 조사의뢰서와 조사확인서는 유지되며 데이터 관리 화면에서 복구할 수 있습니다.")},
        confirmButton={Button(onClick={confirmDelete=false;scope.launch{vm.deleteCase(c);onBack()}}){Text("삭제")}},
        dismissButton={OutlinedButton(onClick={confirmDelete=false}){Text("취소")}}
    )
    Scaffold(topBar={TopAppBar(title={Text(c.managementNo.ifBlank{"상세정보"})},navigationIcon={TextButton(onClick=onBack){Text("뒤로")}},actions={TextButton(onClick=onForm){Text("조사의뢰서")}})}){pad->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)){
            EditFields(c){c=it}
            OutlinedTextField(c.investigationMemo,{c=c.copy(investigationMemo=it)},label={Text("조사 비고")},minLines=4,modifier=Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical=10.dp)){
                Button(onClick={vm.update(c)}){Text("변경 저장")}
                Spacer(Modifier.width(8.dp))
                Button(onClick={picker.launch("image/*")}){Text("조사확인서 첨부")}
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick={
                    val f=OriginalFileStore.createCameraTarget(ctx,c.year,c.id.toString())
                    pending=f
                    camera.launch(FileProvider.getUriForFile(ctx,"${ctx.packageName}.files",f))
                }){Text("카메라 촬영")}
            }
            Text("첨부 원본 ${atts.size}개",style=MaterialTheme.typography.titleMedium)
            Text("항목을 누르면 저장된 원본을 확대해서 확인할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
            atts.forEach{att->
                OutlinedCard(
                    modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{onAttachment(att)}
                ){
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Column(Modifier.weight(1f)){
                            Text(
                                when(att.type){
                                    "ORIGINAL_REQUEST"->"원본 조사의뢰서"
                                    "CONFIRMATION"->"조사확인서 원본"
                                    else->"첨부 원본"
                                },
                                style=MaterialTheme.typography.titleSmall
                            )
                            Text("${att.originalName}  ${att.width}×${att.height}  ${att.byteSize/1024} KB",style=MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${att.sha256.take(24)}…",style=MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick={onAttachment(att)}){Text("원본 보기")}
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick={confirmDelete=true},modifier=Modifier.fillMaxWidth()){Text("이 조사건 삭제")}
        }
    }
}

@Composable fun RequestFormScreen(c:InvestigationCase,onBack:()->Unit){
    val ctx=LocalContext.current
    var msg by remember{mutableStateOf("")}
    Scaffold(topBar={TopAppBar(title={Text("조사의뢰서")},navigationIcon={TextButton(onClick=onBack){Text("뒤로")}},actions={Button(onClick={msg=RequestPdf.create(ctx,c).absolutePath}){Text("PDF 저장")}})}){pad->
        Column(Modifier.padding(pad).fillMaxSize()){
            if(msg.isNotBlank()) Text("저장 위치: $msg",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp))
            ZoomableRequestDocument(c,Modifier.weight(1f))
        }
    }
}

@Composable fun FormRow(a:String,b:String,c:String,d:String){
    Row(Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outline)){
        Column(Modifier.weight(1f).padding(8.dp)){Text(a,style=MaterialTheme.typography.labelSmall);Text(b)}
        Column(Modifier.weight(1f).padding(8.dp)){Text(c,style=MaterialTheme.typography.labelSmall);Text(d)}
    }
}

@Composable fun SettingsScreen(vm:AppViewModel,onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val year by vm.year.collectAsStateWithLifecycle()
    val sync by vm.cloudSync.collectAsStateWithLifecycle()
    val deletedCases by vm.deletedCases.collectAsStateWithLifecycle()
    var msg by remember{mutableStateOf("")}
    val lastSync = sync.lastSuccessAt?.let {
        remember(it) { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)) }
    }
    Scaffold(topBar={TopAppBar(title={Text("데이터 및 동기화")},navigationIcon={TextButton(onClick=onBack){Text("뒤로")}})}){pad->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ){
            Text("Google 계정 동기화", style=MaterialTheme.typography.titleLarge)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    when {
                        !sync.configured -> {
                            Text("연결 설정 필요", fontWeight=FontWeight.SemiBold)
                            Text("현재 설치본에는 Firebase 연결 정보가 없습니다. 연결 설정이 포함된 APK에서 Google 로그인을 사용할 수 있습니다.", style=MaterialTheme.typography.bodySmall)
                        }
                        sync.account == null -> {
                            Text("로그인되지 않음", fontWeight=FontWeight.SemiBold)
                            Text("같은 Google 계정으로 로그인한 기기끼리 조사 데이터와 첨부 원본을 동기화합니다.", style=MaterialTheme.typography.bodySmall)
                            Button(
                                enabled=!sync.syncing && ctx is Activity,
                                onClick={ (ctx as? Activity)?.let(vm::signIn) }
                            ){Text("Google 계정으로 로그인")}
                        }
                        else -> {
                            Text(sync.account?.displayName?.ifBlank { "Google 계정" }.orEmpty(), fontWeight=FontWeight.SemiBold)
                            Text(sync.account?.email.orEmpty(), style=MaterialTheme.typography.bodySmall)
                            if(lastSync!=null) Text("마지막 완료: $lastSync", style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=!sync.syncing,onClick=vm::syncNow){Text("지금 동기화")}
                                OutlinedButton(
                                    enabled=!sync.syncing && ctx is Activity,
                                    onClick={ (ctx as? Activity)?.let(vm::signOut) }
                                ){Text("로그아웃")}
                            }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(sync.syncing) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        Text(sync.message, style=MaterialTheme.typography.bodySmall)
                    }
                    if(sync.lastSuccessAt!=null) {
                        Text(
                            "이번 동기화: 조사 업로드 ${sync.uploadedCases} · 다운로드 ${sync.downloadedCases} / 원본 업로드 ${sync.uploadedAttachments} · 다운로드 ${sync.downloadedAttachments}",
                            style=MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        "로그인 후 변경사항은 자동 동기화됩니다. 통신 중에도 로컬 데이터가 기준으로 유지되며, 서버에는 해당 Google 계정의 UID 경로로만 저장됩니다.",
                        style=MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text("휴지통", style=MaterialTheme.typography.titleLarge)
            Text("삭제한 조사건과 원본은 바로 지우지 않아 다른 기기에서 되살아나는 문제를 막고 필요할 때 복구할 수 있습니다.", style=MaterialTheme.typography.bodySmall)
            if(deletedCases.isEmpty()) {
                Text("휴지통이 비어 있습니다.", style=MaterialTheme.typography.bodySmall)
            } else {
                deletedCases.forEach { deleted ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(deleted.managementNo.ifBlank { deleted.debtorName.ifBlank { "조사건" } }, fontWeight=FontWeight.SemiBold)
                                Text("${deleted.year}년 · ${deleted.propertyAddress}", style=MaterialTheme.typography.bodySmall, maxLines=2, overflow=TextOverflow.Ellipsis)
                            }
                            TextButton(onClick={vm.restoreCase(deleted)}){Text("복구")}
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("연도별 내보내기", style=MaterialTheme.typography.titleLarge)
            Text("${year}년 데이터를 원본 사진의 해상도/바이트를 변경하지 않고 ZIP으로 묶습니다.")
            Button(onClick={scope.launch{
                val r=ArchiveService.exportYear(ctx,vm.db,year)
                msg="${r.cases}건 / 첨부 ${r.attachments}개 / 검증 ${if(r.verified)"완료" else "실패"}\n${r.file.absolutePath}"
            }}){Text("${year}년 데이터 내보내기")}
            Text("ZIP에는 DB JSON, CSV 목록, 모든 원본 파일, SHA-256 manifest가 포함됩니다. 검증 실패 시 원본을 삭제하지 마십시오.")
            if(msg.isNotBlank()) Text("\n$msg")
            Spacer(Modifier.height(12.dp))
        }
    }
}
