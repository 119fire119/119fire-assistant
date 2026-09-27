const KEY="119fire_hybrid_data_v1";
let db=JSON.parse(localStorage.getItem(KEY)||'{"leads":[],"tasks":[],"photos":[],"recordings":[],"chat":[]}');
let native={folderConnected:false,monitorEnabled:false,lastScan:0};
let selectedRecording=null;

function save(){localStorage.setItem(KEY,JSON.stringify(db));render()}
function toastMsg(s){toast.textContent=s;toast.className="toast show";setTimeout(()=>toast.className="toast",1700)}
function showTab(id){document.querySelectorAll(".tab").forEach(x=>x.classList.remove("active"));document.getElementById(id).classList.add("active");document.querySelectorAll("nav button").forEach(x=>x.classList.toggle("active",x.dataset.tab===id));if(id==="settings")refreshNative()}
function escapeHtml(s=""){return String(s).replace(/[&<>"']/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[m]))}

function render(){
 leadCount.textContent=db.leads.length;taskCount.textContent=db.tasks.filter(x=>!x.done).length;recordCount.textContent=db.recordings.length;photoCount.textContent=db.photos.length;
 let r=[...db.leads.slice(-3).reverse().map(x=>`<div class="item"><b>📞 ${escapeHtml(x.name)}</b><p>${escapeHtml(x.memo)}</p></div>`),...db.photos.slice(-2).reverse().map(x=>`<div class="item"><b>📷 ${escapeHtml(x.jobName)}</b><p>${escapeHtml(x.name)}</p></div>`)];
 recent.innerHTML=r.length?r.join(""):'<div class="item"><p>아직 기록이 없습니다.</p></div>';
 photosList.innerHTML=db.photos.length?db.photos.slice().reverse().map(x=>`<div class="item"><b>📷 ${escapeHtml(x.jobName)}</b><p>${escapeHtml(x.name)}<br>${escapeHtml(x.path)}</p></div>`).join(""):'<div class="item"><p>촬영한 현장사진이 없습니다.</p></div>';
 recordings.innerHTML=db.recordings.length?db.recordings.map((x,i)=>`<div class="item"><b>🎙 ${escapeHtml(x.name)}</b><p>${new Date(x.modified).toLocaleString("ko-KR")} · ${(x.size/1024/1024).toFixed(2)}MB</p><div class="actions"><button onclick="selectRecording(${i})">선택</button><button onclick="transcribe(${i})">AI 요약</button></div></div>`).join(""):'<div class="item"><p>아직 찾은 녹음파일이 없습니다.</p></div>';
 chat.innerHTML=db.chat.length?db.chat.map(x=>`<div class="bubble ${x.role==="user"?"me":""}">${escapeHtml(x.text)}</div>`).join(""):'<div class="bubble">안녕하세요. 앱에 저장된 업무 데이터를 기준으로 정리합니다.</div>';
 folderState.textContent=native.folderConnected?"연결됨":"미연결";monitorState.textContent=native.monitorEnabled?"켜짐":"꺼짐";monitorBtn.textContent=native.monitorEnabled?"자동 감지 끄기":"자동 감지 켜기";
}
function newLead(){let name=prompt("고객/현장명");if(!name)return;let memo=prompt("문의 내용")||"";db.leads.push({id:Date.now(),name,memo,created:Date.now()});save();toastMsg("문의 저장")}
function takePhoto(){let job=currentJob.value.trim()||prompt("현장명을 입력하세요")||"";if(!job)return;currentJob.value=job;Native.takePhoto(job)}
function scanRecordings(){Native.scanRecordings();toastMsg("녹음파일 확인 중")}
function toggleMonitor(){native.monitorEnabled?Native.disableMonitor():Native.enableMonitor()}
function selectRecording(i){selectedRecording=db.recordings[i];toastMsg("녹음 선택: "+selectedRecording.name)}
function settings(){return {server:localStorage.getItem("serverBase")||"",code:localStorage.getItem("accessCode")||""}}
function transcribe(i){let s=settings();if(!s.server||!s.code){showTab("settings");toastMsg("서버 주소와 접속 코드를 저장하세요");return}selectedRecording=db.recordings[i];showTab("ai");db.chat.push({role:"user",text:`통화녹음 '${selectedRecording.name}' 내용을 요약해줘.`});save();db.chat.push({role:"assistant",text:"녹음파일을 전사·요약하는 중..."});save();Native.transcribeRecording(selectedRecording.uri,s.server,s.code)}
function context(){return JSON.stringify({leads:db.leads,tasks:db.tasks,photos:db.photos.map(x=>({jobName:x.jobName,name:x.name,created:x.created})),recordings:db.recordings.map(x=>({name:x.name,modified:x.modified,size:x.size})),today:new Date().toISOString()})}
function askAi(){let m=aiInput.value.trim();if(!m)return;quickAi(m);aiInput.value=""}
function quickAi(m){let s=settings();if(!s.server||!s.code){showTab("settings");toastMsg("서버 주소와 접속 코드를 저장하세요");return}showTab("ai");db.chat.push({role:"user",text:m});save();db.chat.push({role:"assistant",text:"정리 중..."});save();Native.askAi(s.server,s.code,m,context())}
function saveSettings(){localStorage.setItem("serverBase",serverBase.value.trim());localStorage.setItem("accessCode",accessCode.value.trim());toastMsg("설정 저장")}
function refreshNative(){try{native=JSON.parse(Native.getStatus());nativeStatus.textContent=JSON.stringify(native,null,2);render()}catch(e){nativeStatus.textContent=e.message}}
window.onNativeStatus=function(raw){native=JSON.parse(raw);render();if(document.getElementById("settings").classList.contains("active"))nativeStatus.textContent=JSON.stringify(native,null,2)}
window.onCallFolderSelected=function(raw){native=JSON.parse(raw);render();toastMsg("통화녹음 폴더 연결 완료")}
window.onRecordings=function(raw){let x=JSON.parse(raw);if(!x.ok){toastMsg(x.error||"녹음 찾기 실패");return}db.recordings=x.files||[];save();showTab("calls");toastMsg(`녹음 ${db.recordings.length}개 확인`)}
window.onPhotoSaved=function(raw){let x=JSON.parse(raw);if(!x.ok)return;db.photos.push(x);save();showTab("photos");toastMsg("현장사진 저장 완료")}
window.onAiResponse=function(raw){let x=JSON.parse(raw);db.chat=db.chat.filter((v,i,a)=>!(v.role==="assistant"&&v.text==="정리 중..."&&i===a.length-1));db.chat.push({role:"assistant",text:x.ok?(x.reply||"응답 없음"):(x.error||"AI 오류")});save();showTab("ai")}
window.onTranscribeResponse=function(raw){let x=JSON.parse(raw);db.chat=db.chat.filter((v,i,a)=>!(v.role==="assistant"&&v.text==="녹음파일을 전사·요약하는 중..."&&i===a.length-1));let t=x.ok?`[통화 AI 요약]\n${x.summary||""}\n\n[전사]\n${x.transcript||""}`:(x.error||"통화 요약 오류");db.chat.push({role:"assistant",text:t});save();showTab("ai")}
serverBase.value=localStorage.getItem("serverBase")||"";accessCode.value=localStorage.getItem("accessCode")||"";
refreshNative();render();
