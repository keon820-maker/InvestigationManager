@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
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
import java.time.LocalDate

@Composable fun InvestigationApp(vm:AppViewModel){
    var screen by remember{mutableStateOf("main")}
    var formReturn by remember{mutableStateOf("main")}
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
            DetailScreen(vm,it,onBack={screen="main"},onForm={formReturn="detail";screen="form"})
        }
        "form"->vm.selected.collectAsStateWithLifecycle().value?.let{
            RequestFormScreen(it,onBack={screen=formReturn})
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
                        vm.db.attachments().insert(a)
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
    f("조사의뢰자",c.requester){c.copy(requester=it)}
}

@Composable fun DetailScreen(vm:AppViewModel,c0:InvestigationCase,onBack:()->Unit,onForm:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var c by remember(c0){mutableStateOf(c0)}
    val atts by vm.db.attachments().observe(c.id).collectAsStateWithLifecycle(emptyList())
    var pending by remember{mutableStateOf<File?>(null)}
    var confirmDelete by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){u->if(u!=null)scope.launch{vm.db.attachments().insert(OriginalFileStore.copyOriginal(ctx,u,c.id,c.year,"CONFIRMATION").attachment)}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        val f=pending
        if(ok&&f!=null) scope.launch{vm.db.attachments().insert(OriginalFileStore.finalizeCamera(f,c.id,"CONFIRMATION").attachment)}
    }
    if(confirmDelete) AlertDialog(
        onDismissRequest={confirmDelete=false},
        title={Text("조사건 삭제")},
        text={Text("${c.managementNo.ifBlank{"이 조사건"}}을 삭제하시겠습니까?\n연결된 원본 조사의뢰서와 조사확인서 파일도 태블릿에서 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.")},
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
            atts.forEach{
                Text("• ${it.originalName}  ${it.width}×${it.height}  ${it.byteSize/1024} KB\n  SHA-256 ${it.sha256.take(18)}…",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(4.dp))
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
    val vertical=rememberScrollState()
    val horizontal=rememberScrollState()
    Scaffold(topBar={TopAppBar(title={Text("조사의뢰서")},navigationIcon={TextButton(onClick=onBack){Text("뒤로")}},actions={Button(onClick={msg=RequestPdf.create(ctx,c).absolutePath}){Text("PDF 저장")}})}){pad->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(Color(0xFFECECEC))
                .verticalScroll(vertical)
                .horizontalScroll(horizontal)
                .padding(16.dp)
        ){
            Column{
                RequestDocumentView(c)
                if(msg.isNotBlank()) Text("저장 위치: $msg",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
            }
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
    var msg by remember{mutableStateOf("")}
    Scaffold(topBar={TopAppBar(title={Text("연도별 데이터 관리")},navigationIcon={TextButton(onClick=onBack){Text("뒤로")}})}){pad->
        Column(Modifier.padding(pad).padding(20.dp)){
            Text("${year}년 데이터를 원본 사진의 해상도/바이트를 변경하지 않고 ZIP으로 묶습니다.")
            Spacer(Modifier.height(12.dp))
            Button(onClick={scope.launch{
                val r=ArchiveService.exportYear(ctx,vm.db,year)
                msg="${r.cases}건 / 첨부 ${r.attachments}개 / 검증 ${if(r.verified)"완료" else "실패"}\n${r.file.absolutePath}"
            }}){Text("${year}년 데이터 내보내기")}
            Spacer(Modifier.height(12.dp))
            Text("ZIP에는 DB JSON, CSV 목록, 모든 원본 파일, SHA-256 manifest가 포함됩니다. 검증 실패 시 원본을 삭제하지 마십시오.")
            if(msg.isNotBlank()) Text("\n$msg")
        }
    }
}
