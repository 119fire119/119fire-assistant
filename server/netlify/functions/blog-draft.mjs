import seo21 from "../../config/seo21.rules.json" with { type: "json" };

function outputText(data){
  if(typeof data.output_text==="string"&&data.output_text) return data.output_text;
  return (data.output||[]).flatMap(x=>x.content||[]).filter(x=>x.type==="output_text").map(x=>x.text||"").join("\n").trim();
}

export default async request => {
  const headers={"content-type":"application/json; charset=utf-8","cache-control":"no-store"};
  if(request.method!=="POST") return new Response(JSON.stringify({error:"POST only"}),{status:405,headers});
  const key=Netlify.env.get("OPENAI_API_KEY")||Netlify.env.get("OPENAi_api_key"), code=Netlify.env.get("APP_ACCESS_CODE");
  if(!key||!code) return new Response(JSON.stringify({error:"서버 환경변수 설정이 필요합니다."}),{status:500,headers});
  let body={};try{body=await request.json()}catch{}
  if(body.code!==code) return new Response(JSON.stringify({error:"앱 접속 코드가 맞지 않습니다."}),{status:401,headers});
  const facts=JSON.stringify(body.facts||{}).slice(0,50000);
  const instructions=`너는 119파이어 블로그 초안 작성 비서다. 아래 SEO 규칙과 전달된 실제 현장 사실만 사용한다. 자료가 부족하면 내용을 만들어내지 말고 '확인 필요'로 표시한다. 기존 글을 수정하지 말고 신규 초안만 만든다. 제목, 인삿말, 시공배경, 공사 전·중·후, FAQ, 마무리, 문의, 해시태그의 순서를 지키고 최종에 [자동검수]를 붙여 부족한 사실·과도한 키워드 여부를 적는다.\n\n[SEO 규칙]\n${JSON.stringify(seo21)}`;
  try{
    const r=await fetch("https://api.openai.com/v1/responses",{method:"POST",headers:{authorization:`Bearer ${key}`,"content-type":"application/json"},body:JSON.stringify({model:Netlify.env.get("OPENAI_MODEL")||"gpt-5",instructions,input:`[실제 현장 자료]\n${facts}`,max_output_tokens:3200,store:false})});
    const data=await r.json();
    if(!r.ok)return new Response(JSON.stringify({error:data?.error?.message||"블로그 초안 생성에 실패했습니다."}),{status:r.status,headers});
    return new Response(JSON.stringify({seoVersion:seo21.version,draft:outputText(data)}),{headers});
  }catch(e){return new Response(JSON.stringify({error:"블로그 초안 생성에 실패했습니다."}),{status:500,headers});}
};
