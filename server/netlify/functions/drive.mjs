const H={"content-type":"application/json; charset=utf-8","cache-control":"no-store"};
const FOLDER_MIME="application/vnd.google-apps.folder";

function json(body,status=200){return new Response(JSON.stringify(body),{status,headers:H});}
function safeName(value){return String(value||"").replace(/[\\/:*?"<>|]/g,"_").trim();}

async function accessToken(){
  const clientId=Netlify.env.get("GOOGLE_DRIVE_CLIENT_ID");
  const clientSecret=Netlify.env.get("GOOGLE_DRIVE_CLIENT_SECRET");
  const refreshToken=Netlify.env.get("GOOGLE_DRIVE_REFRESH_TOKEN");
  if(!clientId||!clientSecret||!refreshToken) throw new Error("Google Drive OAuth 환경변수 설정이 필요합니다.");
  const body=new URLSearchParams({client_id:clientId,client_secret:clientSecret,refresh_token:refreshToken,grant_type:"refresh_token"});
  const r=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body});
  const d=await r.json(); if(!r.ok||!d.access_token) throw new Error(d?.error_description||"Google Drive 인증에 실패했습니다.");
  return d.access_token;
}
async function drive(token,path,options={}){
  const target=path.startsWith("http")?path:`https://www.googleapis.com/drive/v3/${path}`;
  const r=await fetch(target,{...options,headers:{authorization:`Bearer ${token}`,...options.headers}});
  const d=await r.json(); if(!r.ok) throw new Error(d?.error?.message||"Google Drive 요청에 실패했습니다."); return d;
}
async function folderByName(token,name,parent){
  const escaped=name.replace(/'/g,"\\'");
  const parentQuery=parent?` and '${parent}' in parents`:"";
  const q=encodeURIComponent(`mimeType='${FOLDER_MIME}' and name='${escaped}' and trashed=false${parentQuery}`);
  const d=await drive(token,`files?q=${q}&fields=files(id,name,parents)&pageSize=10`);
  if(d.files?.length===1) return d.files[0];
  if(d.files?.length>1) throw new Error(`${name} 폴더가 여러 개라 자동 연결할 수 없습니다.`);
  return null;
}
async function ensureFolder(token,name,parent){
  const old=await folderByName(token,name,parent); if(old) return old;
  return drive(token,"files?fields=id,name,parents",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({name,mimeType:FOLDER_MIME,parents:parent?[parent]:undefined})});
}
async function existingFile(token,name,parent){
  const escaped=name.replace(/'/g,"\\'");
  const q=encodeURIComponent(`name='${escaped}' and '${parent}' in parents and trashed=false`);
  const d=await drive(token,`files?q=${q}&fields=files(id,name,webViewLink,parents)&pageSize=2`);
  return d.files?.length===1?d.files[0]:null;
}
async function ensureSitePath(token,year,month,siteName){
  const root=Netlify.env.get("GOOGLE_DRIVE_ROOT_FOLDER_ID")||"";
  const y=await ensureFolder(token,safeName(year),root);
  const m=await ensureFolder(token,safeName(month),y.id);
  const site=await ensureFolder(token,safeName(siteName),m.id);
  return {year:y,month:m,site};
}
async function upload(token,file,metadata){
  const boundary=`119fire-${Date.now()}`;
  const contentType=file.type||"application/octet-stream";
  const body=new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n`,
    `--${boundary}\r\nContent-Type: ${contentType}\r\n\r\n`,file,`\r\n--${boundary}--\r\n`
  ],{type:`multipart/related; boundary=${boundary}`});
  return drive(token,"https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink,parents",{method:"POST",headers:{"content-type":body.type},body});
}

export default async request=>{
  if(request.method!=="POST") return json({error:"POST only"},405);
  const code=request.headers.get("x-app-code")||"";
  const appCode=Netlify.env.get("APP_ACCESS_CODE");
  if(!appCode||code!==appCode) return json({error:"앱 접속 코드가 맞지 않습니다."},401);
  let form; try{form=await request.formData();}catch{return json({error:"요청 형식이 올바르지 않습니다."},400);}
  const action=String(form.get("action")||"");
  try{
    const token=await accessToken();
    if(action==="status") return json({connected:true});
    if(action==="search"){
      const query=String(form.get("query")||"").trim().replace(/'/g,"\\'");
      if(!query) return json({files:[]});
      const q=encodeURIComponent(`name contains '${query}' and trashed=false`);
      const found=await drive(token,`files?q=${q}&fields=files(id,name,mimeType,modifiedTime,webViewLink,parents)&orderBy=modifiedTime desc&pageSize=20`);
      return json({files:found.files||[]});
    }
    if(action==="upload-photo"){
      const file=form.get("file"); const siteName=safeName(form.get("siteName"));
      const year=safeName(form.get("year")); const month=safeName(form.get("month"));
      if(!file||typeof file.arrayBuffer!=="function"||!siteName||!year||!month) return json({error:"사진 또는 현장 정보가 없습니다."},400);
      const path=await ensureSitePath(token,year,month,siteName);
      const duplicate=await existingFile(token,safeName(file.name)||"현장사진.jpg",path.site.id);
      if(duplicate) return json({driveFileId:duplicate.id,driveFolderId:path.site.id,siteFolderName:path.site.name,deduplicated:true});
      const saved=await upload(token,file,{name:safeName(file.name)||"현장사진.jpg",parents:[path.site.id]});
      return json({driveFileId:saved.id,driveFolderId:path.site.id,siteFolderName:path.site.name});
    }
    return json({error:"지원하지 않는 Drive 작업입니다."},400);
  }catch(e){return json({error:e.message||"Google Drive 처리에 실패했습니다."},503);}
};
