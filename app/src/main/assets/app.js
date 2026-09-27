const LEGACY_KEY="119fire_hybrid_data_v1";
let workspace={inquiries:[],sites:[],tasks:[],estimates:[],photos:[],calls:[],status:{}};
let chat=[{role:"assistant",text:"119파이어 비서입니다. 저장된 업무 기록을 기준으로 확인해드릴게요."}];
let estimateRows=[{name:"",quantity:1,unitPrice:""}];
let voiceMode="assistant";
const money=n=>Number(n||0).toLocaleString("ko-KR")+"원";
const esc=s=>String(s||"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#039;"}[c]));
function nativeCall(name,...args){try{return window.Native&&Native[name]?Native[name](...args):null}catch(e){toastMsg("앱 기능을 사용할 수 없습니다.");return null}}
function parse(v,fallback={}){try{return JSON.parse(v)}catch(e){return fallback}}
function toastMsg(text){toast.textContent=text;toast.className="toast show";setTimeout(()=>toast.className="toast",2400)}
function showTab(id){document.querySelectorAll(".tab").forEach(x=>x.classList.remove("active"));document.getElementById(id).classList.add("active");document.querySelectorAll("nav button").forEach(x=>x.classList.toggle("active",x.dataset.tab===id));if(id!=="home") refreshWorkspace()}
function toggleForm(id){document.getElementById(id).classList.toggle("hidden")}
function refreshWorkspace(){const got=parse(nativeCall("getWorkspace"),null);if(got){workspace=got;render()} }
function render(){
  const d=parse(nativeCall("getDashboard"),{}); workspace.status=workspace.status||d.status||{};
  const now=new Date(); todayLabel.textContent=now.toLocaleDateString("ko-KR",{month:"long",day:"numeric",weekday:"short"})+" · 119파이어 업무";
  metrics.innerHTML=[['오늘 현장',d.sites?.length||0],['할 일',d.openTasks||0],['새 통화',d.waitingCalls||0],['수금확인',d.receivables||0]].map(x=>`<div class="metric"><b>${x[1]}</b><span>${x[0]}</span></div>`).join("");
  briefing.textContent=`오늘은 할 일 ${d.openTasks||0}건, 새 통화 분석 ${d.waitingCalls||0}건, 수금 확인 ${d.receivables||0}건입니다.`;
  priorityTasks.innerHTML=(d.tasks||[]).length?(d.tasks||[]).map(taskCard).join(""):'<div class="item"><b>지금 급한 미처리 업무가 없습니다.</b><p>새 통화와 오늘 현장을 한 번 확인해보세요.</p></div>';
  recentWork.innerHTML=[...(d.sites||[]).map(s=>`<div class="item"><b>▣ ${esc(s.name)}</b><p>${esc(s.status)}${s.address?' · '+esc(s.address):''}</p></div>`),...(d.calls||[]).slice(0,2).map(c=>`<div class="item"><b>🎙 ${esc(c.name)}</b><p>${esc(c.status||'대기')}</p></div>`)].join("")||'<div class="item"><p>아직 연결된 업무가 없습니다.</p></div>';
  inquiryList.innerHTML=(workspace.inquiries||[]).map(x=>`<div class="item"><b>${esc(x.title)}</b><p>${esc(x.content)}</p><div class="meta">${esc(x.status)} · ${esc(x.source)}</div></div>`).join("")||'<div class="item"><p>등록된 문의가 없습니다.</p></div>';
  siteList.innerHTML=(workspace.sites||[]).map(s=>`<div class="item"><b>▣ ${esc(s.name)}</b><p>${esc(s.address||'주소 확인 필요')}<br>${esc(s.status)}</p>${s.workNote?`<div class="meta">작업기록: ${esc(s.workNote)}</div>`:''}</div>`).join("")||'<div class="item"><p>현장 사진을 찍거나 문의를 등록하면 현장이 연결됩니다.</p></div>';
  finishSiteSelect.innerHTML='<option value="">현장 선택</option>'+(workspace.sites||[]).map(s=>`<option value="${s.id}">${esc(s.name)} · ${esc(s.status)}</option>`).join("");
  photoList.innerHTML=(workspace.photos||[]).slice(0,10).map(p=>`<div class="item"><b>📷 ${esc(p.name)}</b><p>${esc(p.category)} · ${new Date(p.createdAt).toLocaleString('ko-KR')}</p></div>`).join("")||'<div class="item"><p>촬영한 사진이 없습니다.</p></div>';
  estimateList.innerHTML=(workspace.estimates||[]).map(x=>`<div class="item"><b>${esc(x.title)}</b><p>v${x.version} · ${esc(x.status)} · 합계 ${money(x.total)}</p><button class="secondary" onclick="exportEstimate(${x.id})">PDF 만들기</button></div>`).join("")||'<div class="item"><p>저장된 견적 초안이 없습니다.</p></div>';
  recordingInfo.textContent=workspace.status?.folderConnected?'통화녹음 폴더가 연결되었습니다. 새 파일은 중복 없이 분석 대기열에 들어갑니다.':'삼성 통화녹음 폴더를 1회 연결하면 새 파일을 중복 없이 대기열에 넣습니다.';
  voiceReply.checked=workspace.status?.voiceReply!==false;monitorEnabled.checked=!!workspace.status?.monitorEnabled;
  renderEstimate();renderChat();
}
function taskCard(t){return `<div class="item priority"><div class="taskrow"><div><b>${esc(t.title)}</b><p>${t.dueAt?esc(t.dueAt):'날짜 확인 필요'} · ${esc(t.source)}</p></div><button onclick="completeTask(${t.id})">완료</button></div></div>`}
function saveInquiry(e){e.preventDefault();const input={customerName:customerName.value,phone:customerPhone.value,siteName:siteName.value,address:siteAddress.value,content:inquiryContent.value,source:inquirySource.value,dueAt:inquiryDue.value};const r=parse(nativeCall('saveInquiry',JSON.stringify(input)));toastMsg(r.message||'저장했습니다.');if(r.ok){e.target.reset();e.target.classList.add('hidden');refreshWorkspace()}}
function completeTask(id){const r=parse(nativeCall('completeTask',id));toastMsg(r.message||'처리했습니다.');refreshWorkspace()}
function takePhoto(){const name=currentJob.value.trim();if(!name){toastMsg('현재 현장명을 먼저 넣어주세요.');return}nativeCall('takePhoto',name)}
function finishSite(){const id=Number(finishSiteSelect.value);if(!id){toastMsg('마감할 현장을 선택하세요.');return}const r=parse(nativeCall('finishSite',id,finishNote.value));toastMsg(r.message||'정리했습니다.');if(r.ok){finishNote.value='';refreshWorkspace()}}
function saveVoiceMemo(){voiceMode='memo';nativeCall('startVoiceAssistant')}
function addEstimateRow(){estimateRows.push({name:'',quantity:1,unitPrice:''});renderEstimate()}
function removeEstimateRow(i){estimateRows.splice(i,1);if(!estimateRows.length)estimateRows=[{name:'',quantity:1,unitPrice:''}];renderEstimate()}
function updateRow(i,k,v){estimateRows[i][k]=v;renderEstimate()}
function renderEstimate(){if(!estimateRows.length)return;estimateRowsEl=estimateRows;estimateRowsContainer=document.getElementById('estimateRows');estimateRowsContainer.innerHTML=estimateRows.map((r,i)=>`<div class="estimate-row"><input value="${esc(r.name)}" placeholder="항목" oninput="updateRow(${i},'name',this.value)"><input value="${esc(r.quantity)}" inputmode="decimal" oninput="updateRow(${i},'quantity',this.value)"><input value="${esc(r.unitPrice)}" inputmode="numeric" placeholder="단가" oninput="updateRow(${i},'unitPrice',this.value)"><button type="button" onclick="removeEstimateRow(${i})">×</button></div>`).join('');const supply=estimateRows.reduce((a,x)=>a+(Number(x.quantity)||0)*(Number(String(x.unitPrice).replace(/,/g,''))||0),0);supplyTotal.textContent=money(supply);vatTotal.textContent=money(Math.round(supply*.1));grandTotal.textContent=money(supply+Math.round(supply*.1))}
function saveEstimate(e){e.preventDefault();const items=estimateRows.map(x=>({name:x.name,quantity:Number(x.quantity),unitPrice:Number(String(x.unitPrice).replace(/,/g,''))})).filter(x=>x.name&&x.quantity>0);const r=parse(nativeCall('saveEstimate',JSON.stringify({title:estimateTitle.value,items})));toastMsg(r.message||'저장했습니다.');if(r.ok){estimateTitle.value='';estimateRows=[{name:'',quantity:1,unitPrice:''}];refreshWorkspace()}}
function exportEstimate(id){const r=parse(nativeCall('exportEstimatePdf',id));toastMsg(r.message||'PDF 생성 완료')}
function startVoice(){voiceMode='assistant';nativeCall('startVoiceAssistant')}
function askAssistant(){const m=askText.value.trim();if(m){askText.value='';quickAsk(m)}}
function quickAsk(message){showTab('assistant');chat.push({role:'user',text:message});renderChat();const set=loadSettings();if(!set.server||!set.code){const local=localAnswer(message);chat.push({role:'assistant',text:local+'\n\nAI 서버를 연결하면 통화 전사와 더 자세한 기록 요약도 사용할 수 있습니다.'});renderChat();return}chat.push({role:'assistant',text:'저장된 기록을 확인 중입니다…'});renderChat();nativeCall('askAi',set.server,set.code,message,JSON.stringify(workspace))}
function localAnswer(message){const d=parse(nativeCall('getDashboard'),{});if(message.includes('지금 뭐')||message.includes('뭐부터')){const t=(d.tasks||[])[0];return t?`가장 먼저: ${t.title}${t.dueAt?' ('+t.dueAt+')':''}`:'기한이 지난 할 일은 없습니다. 오늘 현장과 새 통화를 먼저 확인하세요.'}return `현재 저장 기록 기준으로 할 일 ${d.openTasks||0}건, 새 통화 분석 ${d.waitingCalls||0}건, 수금 확인 ${d.receivables||0}건입니다.`}
function renderChat(){const panel=document.getElementById('chat');if(!panel)return;panel.innerHTML=chat.map(x=>`<div class="bubble ${x.role==='user'?'me':''}">${esc(x.text)}</div>`).join('');panel.scrollTop=panel.scrollHeight}
function organizeToday(){const d=parse(nativeCall('getDashboard'),{});const text=`오늘 신규 문의 ${d.inquiries||0}건, 미처리 할 일 ${d.openTasks||0}건, 새 통화 분석 ${d.waitingCalls||0}건, 수금 확인 ${d.receivables||0}건입니다. 우선 기한 지난 약속과 오늘 현장을 확인하세요.`;chat.push({role:'assistant',text});showTab('assistant');renderChat();if(workspace.status?.voiceReply!==false)nativeCall('speak',text)}
function scanRecordings(){nativeCall('scanRecordings');toastMsg('통화녹음 폴더를 확인 중입니다.')}
function processPendingCalls(){nativeCall('processPendingCallAnalysis');toastMsg('대기 통화 분석을 시작했습니다.')}
function toggleMonitor(enabled){if(enabled)nativeCall('enableMonitor');else nativeCall('disableMonitor');setTimeout(refreshWorkspace,300)}
function setVoiceReply(enabled){nativeCall('setVoiceReply',enabled)}
function loadSettings(){return{server:localStorage.getItem('serverBase')||'',code:localStorage.getItem('accessCode')||''}}
function saveSettings(){localStorage.setItem('serverBase',serverBase.value.trim());localStorage.setItem('accessCode',accessCode.value.trim());nativeCall('saveServerConnection',serverBase.value.trim(),accessCode.value.trim());toastMsg('AI 서버 연결 정보를 저장했습니다.')}
function migrateV1(){const r=parse(nativeCall('migrateV1Data',localStorage.getItem(LEGACY_KEY)||'{}'));toastMsg(r.message||'이전을 확인했습니다.');refreshWorkspace()}
window.onNativeStatus=()=>refreshWorkspace();window.onCallFolderSelected=()=>{toastMsg('통화녹음 폴더를 연결했습니다.');refreshWorkspace()};window.onRecordings=raw=>{const r=parse(raw);toastMsg(r.ok?`통화녹음 ${r.files?.length||0}개를 확인했습니다.`:(r.error||'녹음 확인 실패'));refreshWorkspace()};window.onPhotoSaved=raw=>{const r=parse(raw);toastMsg(r.ok?'현장 사진을 저장했습니다.':'사진 저장에 실패했습니다.');refreshWorkspace()};window.onAiResponse=raw=>{const r=parse(raw);chat=chat.filter(x=>x.text!=='저장된 기록을 확인 중입니다…');chat.push({role:'assistant',text:r.ok?(r.reply||'응답이 없습니다.'):(r.error||'AI 분석에 실패했습니다. 인터넷 연결을 확인한 뒤 다시 시도해주세요.')});renderChat();if(r.ok)nativeCall('speak',r.reply||'')};window.onTranscribeResponse=raw=>{const r=parse(raw);chat.push({role:'assistant',text:r.ok?`[통화 요약]\n${r.summary||'확인 필요'}\n\n[전사]\n${r.transcript||''}`:(r.error||'전사에 실패했습니다.')});renderChat();refreshWorkspace()};window.onQueuedAnalysis=raw=>{const r=parse(raw);toastMsg(r.message||r.error||'분석 상태를 확인하세요.');refreshWorkspace()};window.onVoiceState=raw=>toastMsg(parse(raw).message||'듣고 있습니다.');window.onVoiceError=raw=>toastMsg(parse(raw).message||'음성을 다시 말씀해주세요.');window.onVoiceText=raw=>{const r=parse(raw);if(!r.ok){toastMsg(r.message||'음성을 이해하지 못했습니다.');return}if(voiceMode==='memo'){finishNote.value=r.message;const saved=parse(nativeCall('saveSiteVoiceMemo',currentJob.value,r.message));voiceMode='assistant';toastMsg(saved.message||'음성 작업기록을 저장했습니다.');refreshWorkspace();}else quickAsk(r.message)};
const saved=loadSettings();serverBase.value=saved.server;accessCode.value=saved.code;nativeCall('saveServerConnection',saved.server,saved.code);refreshWorkspace();renderEstimate();renderChat();
