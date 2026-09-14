from pathlib import Path

PATH = Path("letters/index.html")
source = PATH.read_text(encoding="utf-8")

MARKER = 'const ACTIVE_SAVED_KEY = "whiteSaffron.activeSavedDocumentId.v1";'
START = 'function getSavedDocuments(){ return safeJSON(SAVED_KEY,[]); }'
END = 'document.getElementById("savedSearch").addEventListener("input",renderSavedDocuments);'

new_block = r'''function getSavedDocuments(){ return safeJSON(SAVED_KEY,[]); }

const ACTIVE_SAVED_KEY = "whiteSaffron.activeSavedDocumentId.v1";
let activeSavedDocumentId = localStorage.getItem(ACTIVE_SAVED_KEY) || "";
let autoTransferWording = false;
let applyingStandardTransferWording = false;

function setActiveSavedDocumentId(id){
  activeSavedDocumentId=id||"";
  if(activeSavedDocumentId) localStorage.setItem(ACTIVE_SAVED_KEY,activeSavedDocumentId);
  else localStorage.removeItem(ACTIVE_SAVED_KEY);
  updateSavedDocumentButtons();
}
function updateSavedDocumentButtons(){
  const editing=Boolean(activeSavedDocumentId);
  const saveBtn=document.getElementById("saveDocBtn");
  const saveViewBtn=document.getElementById("saveCurrentFromViewBtn");
  if(saveBtn) saveBtn.textContent=editing ? "Save Changes" : "Save Document";
  if(saveViewBtn) saveViewBtn.textContent=editing ? "Save Changes" : "Save Current Document";
}
function sameSavedDocumentData(item,page,data){
  return Boolean(item) && item.page===page && JSON.stringify(item.data||{})===JSON.stringify(data);
}
function collapseExactSavedDuplicates(list){
  const seen=new Set();
  return list.filter(item=>{
    const key=JSON.stringify({page:item.page||"",subject:item.subject||item.type||"",data:item.data||{}});
    if(seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
function normalizeSavedDocuments(){
  const list=getSavedDocuments();
  const cleaned=collapseExactSavedDuplicates(list);
  if(cleaned.length!==list.length) putJSON(SAVED_KEY,cleaned);
  return cleaned;
}
function restoreSavedDocumentEditingState(){
  if(activeSavedDocumentId && !getSavedDocuments().some(x=>x.id===activeSavedDocumentId)){
    activeSavedDocumentId="";
    localStorage.removeItem(ACTIVE_SAVED_KEY);
  }
  updateSavedDocumentButtons();
}
function standardTransferWording(){
  const employee=fieldValue("employee")||"the employee";
  const passport=fieldValue("passport");
  const currentEmployer=fieldValue("currentEmployer")||"Cafe' White Saffron";
  const newEmployer=fieldValue("newEmployer")||"the new employer";
  const registration=fieldValue("regNo");
  const destination=registration ? `${newEmployer} (${registration})` : newEmployer;
  return {
    p1:passport
      ? `This is to confirm that ${employee}, Passport No. ${passport}, is currently employed under ${currentEmployer}.`
      : `This is to confirm that ${employee} is currently employed under ${currentEmployer}.`,
    p2:`${currentEmployer} has no objection to the transfer of the above-mentioned employee's employment and work permit to ${destination}. We hereby give our consent for the employer change, subject to the approval and requirements of the relevant authorities of the Republic of Maldives.`,
    p3:"We kindly request the relevant authorities to process the necessary employer change and update the employee’s work permit and employment records accordingly."
  };
}
function applyStandardTransferWording(enableAuto=true,announce=true){
  const wording=standardTransferWording();
  applyingStandardTransferWording=true;
  ["p1","p2","p3"].forEach(key=>{
    const el=document.querySelector(`[data-key="${key}"]`);
    if(el) el.value=wording[key];
  });
  applyingStandardTransferWording=false;
  autoTransferWording=enableAuto;
  sync();
  if(announce) showToast("Standard wording applied");
}
function installTransferWordingTools(){
  const p1=document.querySelector('[data-key="p1"]');
  const section=p1?.closest(".section");
  if(section && !document.getElementById("standardTransferWordingBtn")){
    const row=document.createElement("div");
    row.style.cssText="display:flex;justify-content:flex-end;margin:-2px 0 10px";
    const btn=document.createElement("button");
    btn.type="button";
    btn.id="standardTransferWordingBtn";
    btn.className="mini-btn";
    btn.textContent="Use Standard Wording";
    btn.addEventListener("click",()=>applyStandardTransferWording(true,true));
    row.appendChild(btn);
    const firstField=p1.closest(".field");
    section.insertBefore(row,firstField);
  }
  const resetBtn=document.getElementById("resetBtn");
  if(resetBtn && !document.getElementById("newTransferLetterBtn")){
    const btn=document.createElement("button");
    btn.type="button";
    btn.id="newTransferLetterBtn";
    btn.className="btn-ghost";
    btn.textContent="New Letter";
    btn.addEventListener("click",startNewTransferLetter);
    resetBtn.parentNode.insertBefore(btn,resetBtn);
  }
}
function startNewTransferLetter(){
  if(activeSavedDocumentId && !confirm("Start a new transfer letter? Save any changes first if you want to keep them.")) return;
  setActiveSavedDocumentId("");
  setDocumentPage("transfer");
  ["employee","passport","nationality","employeeDob","employeePhone","newEmployer","regNo","newPhone","newAddress"].forEach(key=>{
    const el=document.querySelector(`[data-key="${key}"]`);
    if(el) el.value="";
  });
  const currentEmployer=document.querySelector('[data-key="currentEmployer"]');
  if(currentEmployer && !currentEmployer.value.trim()) currentEmployer.value="Cafe' White Saffron";
  const subject=document.querySelector('[data-key="subject"]');
  if(subject) subject.value="Employee Transfer Letter";
  const date=document.querySelector('[data-key="date"]');
  if(date){
    date.value=new Date().toLocaleDateString("en-GB",{day:"2-digit",month:"long",year:"numeric"}).replace(/^0/,"");
  }
  const status=document.getElementById("currentStatus");
  if(status){
    status.value="Draft";
    localStorage.setItem("whiteSaffron.currentStatus","Draft");
  }
  autoTransferWording=true;
  applyStandardTransferWording(true,false);
  sync();
  setMainView("documents");
  showToast("New transfer letter ready");
}

["employee","passport","currentEmployer","newEmployer","regNo"].forEach(key=>{
  const el=document.querySelector(`[data-key="${key}"]`);
  if(el) el.addEventListener("input",()=>{
    if(autoTransferWording) applyStandardTransferWording(true,false);
  });
});
["p1","p2","p3"].forEach(key=>{
  const el=document.querySelector(`[data-key="${key}"]`);
  if(el) el.addEventListener("input",()=>{
    if(!applyingStandardTransferWording) autoTransferWording=false;
  });
});

function saveCurrentDocument(){
  ensureReferenceForCurrent();
  const data={}; document.querySelectorAll("[data-key]").forEach(el=>data[el.dataset.key]=el.value);
  const meta=currentDocumentMeta();
  const now=new Date().toISOString();
  let list=normalizeSavedDocuments();
  let idx=activeSavedDocumentId ? list.findIndex(x=>x.id===activeSavedDocumentId) : -1;
  if(idx<0){
    idx=list.findIndex(x=>sameSavedDocumentData(x,meta.page,data));
    if(idx>=0) setActiveSavedDocumentId(list[idx].id);
  }
  if(idx>=0){
    const previous=list[idx];
    if(!meta.ref) meta.ref=previous.ref||"";
    const item={...previous,...meta,id:previous.id,data,savedAt:now};
    list.splice(idx,1);
    list.unshift(item);
    list=collapseExactSavedDuplicates(list);
    putJSON(SAVED_KEY,list);
    setActiveSavedDocumentId(item.id);
    renderSavedDocuments();
    showToast("Changes saved");
    return;
  }
  if(!meta.ref) meta.ref=nextReference(false);
  const item={id:`doc-${Date.now()}`,...meta,data,savedAt:now};
  list.unshift(item);
  list=collapseExactSavedDuplicates(list);
  putJSON(SAVED_KEY,list);
  setActiveSavedDocumentId(item.id);
  renderSavedDocuments();
  showToast("Document saved");
}
function openSavedDocument(id){
  const item=getSavedDocuments().find(x=>x.id===id); if(!item) return;
  Object.entries(item.data||{}).forEach(([k,v])=>{
    const el=document.querySelector(`[data-key="${k}"]`);
    if(el) el.value=v;
  });
  document.getElementById("currentStatus").value=item.status||"Draft";
  autoTransferWording=false;
  setActiveSavedDocumentId(item.id);
  sync();
  setDocumentPage(item.page||"transfer");
  setMainView("documents");
  showToast("Document ready to edit");
}
function duplicateSavedDocument(id){
  const item=getSavedDocuments().find(x=>x.id===id); if(!item) return;
  const copy={...item,id:`doc-${Date.now()}`,status:"Draft",savedAt:new Date().toISOString(),subject:(item.subject||item.type)+" — Copy"};
  const list=getSavedDocuments();
  list.unshift(copy);
  putJSON(SAVED_KEY,list);
  renderSavedDocuments();
  showToast("Document duplicated");
}
function renderSavedDocuments(){
  const q=(document.getElementById("savedSearch")?.value||"").toLowerCase();
  const stored=normalizeSavedDocuments();
  const list=stored.filter(d=>[d.ref,d.employee,d.subject,d.type,d.status].join(" ").toLowerCase().includes(q));
  document.getElementById("savedDocumentList").innerHTML=list.length ? list.map(d=>`
    <div class="saved-card">
      <h4>${escapeHTML(d.subject||d.type)}</h4>
      <p>${escapeHTML(d.type)} · ${escapeHTML(d.employee||d.recipient||"General")}<br>${escapeHTML(d.ref||"No reference")} · ${escapeHTML(d.status)} · ${new Date(d.savedAt).toLocaleString()}</p>
      <div class="record-actions" style="justify-content:flex-start">
        <button class="mini-btn" onclick="openSavedDocument('${d.id}')">Edit / Open</button>
        <button class="mini-btn" onclick="duplicateSavedDocument('${d.id}')">Duplicate</button>
        <button class="mini-btn danger" onclick="deleteSavedDocument('${d.id}')">Delete</button>
      </div>
    </div>`).join("") : '<div class="empty-state">No saved documents yet.</div>';
}
function deleteSavedDocument(id){
  if(!confirm("Delete this saved document?")) return;
  putJSON(SAVED_KEY,getSavedDocuments().filter(x=>x.id!==id));
  if(activeSavedDocumentId===id) setActiveSavedDocumentId("");
  renderSavedDocuments();
}
window.openSavedDocument=openSavedDocument;
window.duplicateSavedDocument=duplicateSavedDocument;
window.deleteSavedDocument=deleteSavedDocument;
document.getElementById("saveDocBtn").addEventListener("click",saveCurrentDocument);
document.getElementById("saveCurrentFromViewBtn").addEventListener("click",()=>{
  saveCurrentDocument();
  renderSavedDocuments();
});
document.getElementById("savedSearch").addEventListener("input",renderSavedDocuments);
installTransferWordingTools();
restoreSavedDocumentEditingState();'''

if MARKER not in source:
    try:
        start = source.index(START)
        end = source.index(END, start) + len(END)
    except ValueError as exc:
        raise SystemExit(f"Could not locate the saved-document block safely: {exc}")
    source = source[:start] + new_block + source[end:]

reset_old = '''document.getElementById("resetBtn").addEventListener("click", () => {
  if (!confirm("Reset all fields to the original transfer letter?")) return;
  localStorage.removeItem(KEY);'''
reset_new = '''document.getElementById("resetBtn").addEventListener("click", () => {
  if (!confirm("Reset all fields to the original transfer letter?")) return;
  if(typeof setActiveSavedDocumentId==="function") setActiveSavedDocumentId("");
  autoTransferWording=false;
  localStorage.removeItem(KEY);'''
if reset_old in source:
    source = source.replace(reset_old, reset_new, 1)

PATH.write_text(source, encoding="utf-8")
print("letters/index.html patched")
