import { useState } from "react";

const C = {
  bg:"#07090f", s1:"#0b0d18", s2:"#0f1220", s3:"#131728", hov:"#121628",
  border:"#181d30", borderHi:"#222840",
  cyan:"#22d3ee", cyanDim:"#22d3ee12", cyanMid:"#22d3ee30",
  green:"#34d399", greenDim:"#34d39910", greenMid:"#34d39930",
  red:"#f87171",  redDim:"#f8717110",  redMid:"#f8717130",
  amber:"#fbbf24", amberDim:"#fbbf2410", amberMid:"#fbbf2430",
  purple:"#a78bfa", purpleDim:"#a78bfa10", purpleMid:"#a78bfa30",
  blue:"#60a5fa",  blueDim:"#60a5fa10",   blueMid:"#60a5fa30",
  text:"#dde3f0", sub:"#4a5878", muted:"#1e2538",
};

const G = `
@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=DM+Sans:wght@400;500;600;700&display=swap');
*{box-sizing:border-box;margin:0;padding:0}
html,body,#root{height:100%;overflow:hidden}
body{background:${C.bg};color:${C.text};font-family:'DM Sans',sans-serif;font-size:13px}
.mono{font-family:'IBM Plex Mono',monospace}
::-webkit-scrollbar{width:3px}::-webkit-scrollbar-thumb{background:${C.borderHi};border-radius:2px}
button{cursor:pointer;border:none;background:none;color:${C.text};font-family:'DM Sans',sans-serif;outline:none}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@keyframes spin{to{transform:rotate(360deg)}}
.aFade{animation:fadeIn .18s ease both}
`;

const Icon = ({n,s=13,c="currentColor"}) => {
  const d = {
    chev_d:  <polyline points="6 9 12 15 18 9"/>,
    chev_r:  <polyline points="9 18 15 12 9 6"/>,
    arr_up:  <><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></>,
    arr_dn:  <><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></>,
    loop:    <><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></>,
    check:   <polyline points="20 6 9 17 4 12"/>,
    lock:    <><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></>,
    unlock:  <><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/></>,
    vault:   <><rect x="5" y="3" width="14" height="18" rx="2"/><circle cx="12" cy="12" r="3"/><path d="M12 9v0M12 15v0"/></>,
    gear:    <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></>,
    lending: <><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></>,
    tokens:  <><circle cx="12" cy="12" r="9"/><path d="M9 12h6M12 9v6"/></>,
    lp:      <><path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"/><path d="M13 13l6 6"/></>,
    staking: <><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></>,
    health:  <><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></>,
    warn:    <><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></>,
  };
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c}
      strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" style={{flexShrink:0}}>
      {d[n]}
    </svg>
  );
};

const Tag = ({children,v="def"}) => {
  const m={def:{bg:C.s2,c:C.sub,b:C.border},cyan:{bg:C.cyanDim,c:C.cyan,b:C.cyanMid},
    green:{bg:C.greenDim,c:C.green,b:C.greenMid},amber:{bg:C.amberDim,c:C.amber,b:C.amberMid},
    red:{bg:C.redDim,c:C.red,b:C.redMid},purple:{bg:C.purpleDim,c:C.purple,b:C.purpleMid},
    blue:{bg:C.blueDim,c:C.blue,b:C.blueMid}};
  const s=m[v]||m.def;
  return <span style={{display:"inline-flex",alignItems:"center",gap:3,padding:"1px 6px",borderRadius:3,
    fontSize:9,fontWeight:700,fontFamily:"'IBM Plex Mono',monospace",letterSpacing:".04em",
    background:s.bg,color:s.c,border:`1px solid ${s.b}`}}>{children}</span>;
};

const fmt$ = v => {
  const abs=Math.abs(v);
  const s=abs>=1000?`$${(abs/1000).toFixed(1)}k`:`$${abs.toFixed(0)}`;
  return (v<0?"-":"")+s;
};
const fmtDuration = (start,end) => {
  const d1=new Date(start),d2=end?new Date(end):new Date();
  const diff=Math.round((d2-d1)/86400000);
  return diff<30?`${diff}d`:diff<365?`${Math.round(diff/30)}mo`:`${(diff/365).toFixed(1)}y`;
};

/* ── HealthBar ── */
const HealthBar = ({hf}) => {
  const n=parseFloat(hf);
  const c=n>=2?C.green:n>=1.2?C.amber:C.red;
  const label=n>=2?"Safe":n>=1.5?"Moderate":n>=1.1?"At risk":"Danger";
  const pct=Math.min(n/3*100,100);
  return (
    <div style={{display:"flex",flexDirection:"column",gap:5}}>
      <div style={{display:"flex",alignItems:"center",gap:8,justifyContent:"space-between"}}>
        <div style={{display:"flex",alignItems:"center",gap:5}}>
          <Icon n="health" s={11} c={c}/>
          <span style={{fontSize:10,color:C.sub}}>Health factor</span>
        </div>
        <div style={{display:"flex",alignItems:"center",gap:6}}>
          <span style={{fontSize:10,fontWeight:600,color:c}}>{label}</span>
          <span className="mono" style={{fontSize:14,fontWeight:800,color:c}}>{n.toFixed(2)}</span>
        </div>
      </div>
      <div style={{position:"relative",height:6,borderRadius:3,overflow:"hidden",
        background:`linear-gradient(to right, ${C.red}, ${C.amber} 40%, ${C.green})`}}>
        <div style={{position:"absolute",top:"-1px",width:3,height:8,background:"#fff",
          borderRadius:2,left:`calc(${pct}% - 1.5px)`,boxShadow:"0 0 4px rgba(0,0,0,.5)"}}/>
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   DATA — cycles per protocol
═══════════════════════════════════════════════════════════ */
const TX_META = {
  LEND_DEPOSIT:  {c:C.green,  bg:C.greenDim,  label:"Supply",    dir:"+", icon:"arr_up"},
  LEND_WITHDRAW: {c:C.red,    bg:C.redDim,    label:"Withdraw",  dir:"-", icon:"arr_dn"},
  BORROW:        {c:C.purple, bg:C.purpleDim, label:"Borrow",    dir:"+", icon:"arr_dn"},
  REPAY:         {c:C.blue,   bg:C.blueDim,   label:"Repay",     dir:"-", icon:"arr_up"},
};

const PROTOCOLS = [
  /* ── AAVE V3 / ETHEREUM ──────────────────────────────────
     No vault concept — one pool, cycles are time-based
  ── */
  {
    id: "aave-eth",
    protocol: "Aave V3",
    net: "ETH",
    netColor: "#627EEA",
    netIcon: "⟠",
    vaultModel: false,       // Aave: no vault grouping
    healthFactor: "1.84",    // only for active cycle
    supplyUsd: 12480,
    borrowUsd: 4320,

    cycles: [
      {
        id: "c1",
        status: "closed",
        opened: "2024-04-10",
        closed: "2024-09-28",
        pnl: 284,              // interest earned - interest paid
        interestEarned: 412,
        interestPaid: 128,
        gasUsd: 41,
        peakSupplyUsd: 7200,
        peakBorrowUsd: 2800,
        summary: "Supply ETH → Borrow USDC → Looping ×2 → Full close",
        txGroups: [
          {
            type: "open",
            date: "2024-04-10",
            items: [
              {type:"LEND_DEPOSIT",asset:"ETH",  qty:"+1.5",  usd:"+$4,050", hash:"0xabc…1001"},
            ],
          },
          {
            type: "borrow",
            date: "2024-04-12",
            items: [
              {type:"BORROW",asset:"USDC",qty:"+2000",usd:"+$2,000",hash:"0xabc…1010"},
            ],
          },
          {
            // looping strategy — collapsed by default
            type: "loop",
            date: "2024-04-12",
            loopSteps: 2,
            loopAssetIn: "USDC",
            loopAssetOut: "ETH",
            items: [
              {type:"LEND_DEPOSIT",asset:"ETH",  qty:"+0.64",usd:"+$1,728",hash:"0xabc…1011"},
              {type:"BORROW",      asset:"USDC",qty:"+800", usd:"+$800",  hash:"0xabc…1012"},
              {type:"LEND_DEPOSIT",asset:"ETH",  qty:"+0.29",usd:"+$783", hash:"0xabc…1013"},
              {type:"BORROW",      asset:"USDC", qty:"+320", usd:"+$320",  hash:"0xabc…1014"},
            ],
          },
          {
            type: "mid",
            date: "2024-07-01",
            items: [
              {type:"REPAY",       asset:"USDC",qty:"-200",usd:"-$200",hash:"0xabc…2001"},
            ],
          },
          {
            type: "close",
            date: "2024-09-28",
            items: [
              {type:"REPAY",        asset:"USDC",qty:"-2920",usd:"-$2,920",hash:"0xabc…3001"},
              {type:"LEND_WITHDRAW",asset:"ETH", qty:"-2.43",usd:"-$6,804",hash:"0xabc…3002"},
            ],
          },
        ],
      },
      {
        id: "c2",
        status: "active",
        opened: "2024-12-01",
        closed: null,
        pnl: null,             // running
        interestEarned: 87,
        interestPaid: 34,
        gasUsd: 18,
        peakSupplyUsd: 12480,
        peakBorrowUsd: 4320,
        summary: "Supply ETH + USDC → Borrow USDC",
        txGroups: [
          {
            type: "open",
            date: "2024-12-01",
            items: [
              {type:"LEND_DEPOSIT",asset:"ETH",  qty:"+2.5",  usd:"+$7,750",hash:"0xabc…4001"},
              {type:"LEND_DEPOSIT",asset:"USDC", qty:"+4730", usd:"+$4,730",hash:"0xabc…4002"},
            ],
          },
          {
            type: "borrow",
            date: "2024-12-03",
            items: [
              {type:"BORROW",asset:"USDC",qty:"+4320",usd:"+$4,320",hash:"0xabc…4010"},
            ],
          },
          {
            type: "mid",
            date: "2025-01-08",
            items: [
              {type:"REPAY",asset:"USDC",qty:"-400",usd:"-$400",hash:"0xabc…4020"},
            ],
          },
        ],
      },
    ],
  },

  /* ── AAVE V3 / OPTIMISM ───────────────────────────────── */
  {
    id: "aave-op",
    protocol: "Aave V3",
    net: "OP",
    netColor: "#FF0420",
    netIcon: "○",
    vaultModel: false,
    healthFactor: "2.41",
    supplyUsd: 3100,
    borrowUsd: 800,

    cycles: [
      {
        id: "c3",
        status: "active",
        opened: "2024-12-10",
        closed: null,
        pnl: null,
        interestEarned: 28,
        interestPaid: 12,
        gasUsd: 4,
        peakSupplyUsd: 3100,
        peakBorrowUsd: 800,
        summary: "Supply WETH → Borrow DAI",
        txGroups: [
          {
            type: "open",
            date: "2024-12-10",
            items: [
              {type:"LEND_DEPOSIT",asset:"WETH",qty:"+1.0",usd:"+$3,100",hash:"0xdef…5501"},
              {type:"BORROW",asset:"DAI",qty:"+800",usd:"+$800",hash:"0xdef…5502"},
            ],
          },
        ],
      },
    ],
  },

  /* ── FLUID / ETHEREUM — vault-based ──────────────────────
     Each vault = separate bucket with its own health factor
  ── */
  {
    id: "fluid-eth",
    protocol: "Fluid",
    net: "ETH",
    netColor: "#627EEA",
    netIcon: "⟠",
    vaultModel: true,        // Fluid: group by vault
    healthFactor: null,      // per-vault

    vaults: [
      {
        id: "v1",
        name: "wstUSR/USDT0",
        token0: "wstUSR",
        token1: "USDT0",
        healthFactor: "1.62",
        supplyUsd: 8400,
        borrowUsd: 5200,

        cycles: [
          {
            id: "vc1",
            status: "active",
            opened: "2025-01-05",
            closed: null,
            pnl: null,
            interestEarned: 124,
            interestPaid: 89,
            gasUsd: 22,
            peakSupplyUsd: 8400,
            peakBorrowUsd: 5200,
            summary: "wstUSR supply → USDT0 borrow → Looping ×3",
            txGroups: [
              {
                type: "open",
                date: "2025-01-05",
                items: [
                  {type:"LEND_DEPOSIT",asset:"wstUSR",qty:"+8400",usd:"+$8,400",hash:"0xflu…0001"},
                ],
              },
              {
                type: "loop",
                date: "2025-01-05",
                loopSteps: 3,
                loopAssetIn: "wstUSR",
                loopAssetOut: "USDT0",
                items: [
                  {type:"BORROW",      asset:"USDT0",qty:"+2000",usd:"+$2,000",hash:"0xflu…0002"},
                  {type:"LEND_DEPOSIT",asset:"wstUSR",qty:"+2000",usd:"+$2,000",hash:"0xflu…0003"},
                  {type:"BORROW",      asset:"USDT0",qty:"+1600",usd:"+$1,600",hash:"0xflu…0004"},
                  {type:"LEND_DEPOSIT",asset:"wstUSR",qty:"+1600",usd:"+$1,600",hash:"0xflu…0005"},
                  {type:"BORROW",      asset:"USDT0",qty:"+1280",usd:"+$1,280",hash:"0xflu…0006"},
                  {type:"LEND_DEPOSIT",asset:"wstUSR",qty:"+1280",usd:"+$1,280",hash:"0xflu…0007"},
                ],
              },
            ],
          },
        ],
      },
      {
        id: "v2",
        name: "wstETH/USDC",
        token0: "wstETH",
        token1: "USDC",
        healthFactor: "3.10",
        supplyUsd: 4200,
        borrowUsd: 800,

        cycles: [
          {
            id: "vc2",
            status: "closed",
            opened: "2024-08-12",
            closed: "2024-11-30",
            pnl: 156,
            interestEarned: 198,
            interestPaid: 42,
            gasUsd: 28,
            peakSupplyUsd: 4200,
            peakBorrowUsd: 1600,
            summary: "wstETH supply → USDC borrow",
            txGroups: [
              {
                type:"open", date:"2024-08-12",
                items:[{type:"LEND_DEPOSIT",asset:"wstETH",qty:"+1.4",usd:"+$4,200",hash:"0xflu…1001"}],
              },
              {
                type:"borrow", date:"2024-08-14",
                items:[{type:"BORROW",asset:"USDC",qty:"+1600",usd:"+$1,600",hash:"0xflu…1010"}],
              },
              {
                type:"close", date:"2024-11-30",
                items:[
                  {type:"REPAY",asset:"USDC",qty:"-1600",usd:"-$1,600",hash:"0xflu…1020"},
                  {type:"LEND_WITHDRAW",asset:"wstETH",qty:"-1.4",usd:"-$4,620",hash:"0xflu…1021"},
                ],
              },
            ],
          },
          {
            id: "vc3",
            status: "active",
            opened: "2025-01-15",
            closed: null,
            pnl: null,
            interestEarned: 14,
            interestPaid: 4,
            gasUsd: 8,
            peakSupplyUsd: 4200,
            peakBorrowUsd: 800,
            summary: "wstETH supply → USDC borrow",
            txGroups: [
              {
                type:"open", date:"2025-01-15",
                items:[{type:"LEND_DEPOSIT",asset:"wstETH",qty:"+1.35",usd:"+$4,200",hash:"0xflu…2001"}],
              },
              {
                type:"borrow", date:"2025-01-16",
                items:[{type:"BORROW",asset:"USDC",qty:"+800",usd:"+$800",hash:"0xflu…2010"}],
              },
            ],
          },
        ],
      },
    ],
  },
];

/* ═══════════════════════════════════════════════════════════
   TX GROUP ROW
═══════════════════════════════════════════════════════════ */
const TxGroupRow = ({group,isLast}) => {
  const [loopExpanded,setLoopExpanded] = useState(false);

  if (group.type === "loop") {
    const steps = group.loopSteps;
    return (
      <div style={{display:"flex",gap:0,alignItems:"stretch"}}>
        {/* Timeline line */}
        <div style={{width:24,flexShrink:0,display:"flex",flexDirection:"column",alignItems:"center"}}>
          <div style={{width:1,flex:1,background:C.amberMid}}/>
        </div>
        <div style={{flex:1,marginBottom:3}}>
          {/* Loop header */}
          <button onClick={()=>setLoopExpanded(e=>!e)} style={{
            width:"100%",display:"flex",alignItems:"center",gap:8,
            padding:"7px 10px",borderRadius:6,textAlign:"left",
            background:C.amberDim,border:`1px solid ${C.amberMid}`,
            transition:"background .1s",
          }}
          onMouseOver={e=>e.currentTarget.style.background=C.amber+"18"}
          onMouseOut={e=>e.currentTarget.style.background=C.amberDim}>
            <div style={{width:24,height:24,borderRadius:5,flexShrink:0,
              background:C.amberDim,border:`1px solid ${C.amberMid}`,
              display:"flex",alignItems:"center",justifyContent:"center"}}>
              <Icon n="loop" s={11} c={C.amber}/>
            </div>
            <div style={{flex:1}}>
              <div style={{display:"flex",alignItems:"center",gap:6}}>
                <span style={{fontSize:11,fontWeight:700,color:C.amber}}>
                  Looping strategy ×{steps}
                </span>
                <Tag v="amber">{steps} steps</Tag>
                <span style={{fontSize:10,color:C.sub}}>
                  {group.loopAssetIn} → {group.loopAssetOut}
                </span>
              </div>
              <div style={{fontSize:9,color:C.sub,marginTop:1}}>{group.date}</div>
            </div>
            <div style={{transform:loopExpanded?"rotate(180deg)":"none",transition:"transform .15s",color:C.amber}}>
              <Icon n="chev_d" s={11} c={C.amber}/>
            </div>
          </button>

          {/* Loop steps expanded */}
          {loopExpanded&&(
            <div className="aFade" style={{marginTop:3,paddingLeft:12,
              borderLeft:`2px solid ${C.amberMid}`,marginLeft:4,display:"flex",flexDirection:"column",gap:2}}>
              {group.items.map((item,i)=>{
                const meta=TX_META[item.type]||{c:C.sub,label:item.type,icon:"arr_up",bg:C.s2};
                return (
                  <div key={i} style={{display:"flex",alignItems:"center",gap:7,
                    padding:"5px 9px",background:C.s2,border:`1px solid ${C.border}`,borderRadius:5}}>
                    <div style={{width:20,height:20,borderRadius:4,flexShrink:0,
                      background:meta.bg,border:`1px solid ${meta.c}30`,
                      display:"flex",alignItems:"center",justifyContent:"center"}}>
                      <Icon n={meta.icon} s={10} c={meta.c}/>
                    </div>
                    <Tag v={item.type==="BORROW"?"purple":item.type==="LEND_DEPOSIT"?"green":"blue"}>
                      {meta.label}
                    </Tag>
                    <span style={{fontSize:11,fontWeight:600}}>{item.asset}</span>
                    <span className="mono" style={{marginLeft:"auto",fontSize:10,
                      color:item.qty.startsWith("+")?C.green:C.red}}>{item.qty}</span>
                    <span className="mono" style={{fontSize:9,color:C.sub}}>{item.usd}</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    );
  }

  /* Regular group */
  const typeColors = {
    open:  {line:C.cyanMid,  dot:C.cyan,  label:"Open"},
    borrow:{line:C.purpleMid,dot:C.purple,label:"Borrow"},
    mid:   {line:C.border,   dot:C.sub,   label:""},
    close: {line:C.redMid,   dot:C.red,   label:"Close"},
  };
  const tc = typeColors[group.type]||typeColors.mid;

  return (
    <div style={{display:"flex",gap:0,alignItems:"stretch"}}>
      {/* Timeline */}
      <div style={{width:24,flexShrink:0,display:"flex",flexDirection:"column",alignItems:"center",paddingTop:8}}>
        <div style={{width:8,height:8,borderRadius:"50%",background:tc.dot,flexShrink:0,
          boxShadow:group.type==="open"||group.type==="close"?`0 0 6px ${tc.dot}`:undefined}}/>
        {!isLast&&<div style={{width:1,flex:1,background:tc.line,marginTop:2}}/>}
      </div>

      {/* Content */}
      <div style={{flex:1,paddingBottom:8}}>
        {group.type!=="mid"&&(
          <div style={{fontSize:8,fontWeight:700,color:tc.dot,textTransform:"uppercase",
            letterSpacing:".1em",marginBottom:5}}>{tc.label} · {group.date}</div>
        )}
        <div style={{display:"flex",flexDirection:"column",gap:2}}>
          {group.items.map((item,i)=>{
            const meta=TX_META[item.type]||{c:C.sub,label:item.type,icon:"arr_up",bg:C.s2};
            const tagV = item.type==="LEND_DEPOSIT"?"green":item.type==="BORROW"?"purple":item.type==="REPAY"?"blue":"red";
            return (
              <div key={i} style={{display:"flex",alignItems:"center",gap:8,
                padding:"6px 10px",background:C.s2,border:`1px solid ${C.border}`,borderRadius:6}}>
                <div style={{width:24,height:24,borderRadius:5,flexShrink:0,
                  background:meta.bg,border:`1px solid ${meta.c}25`,
                  display:"flex",alignItems:"center",justifyContent:"center"}}>
                  <Icon n={meta.icon} s={11} c={meta.c}/>
                </div>
                <Tag v={tagV}>{meta.label}</Tag>
                <span style={{fontSize:11,fontWeight:600}}>{item.asset}</span>
                <span style={{flex:1}}/>
                <span className="mono" style={{fontSize:11,fontWeight:600,
                  color:item.qty.startsWith("+")?C.green:C.red}}>{item.qty} {item.asset}</span>
                <span className="mono" style={{fontSize:9,color:C.sub,width:58,textAlign:"right"}}>{item.usd}</span>
                <span className="mono" style={{fontSize:8,color:C.muted,width:64,textAlign:"right"}}>{item.hash}</span>
              </div>
            );
          })}
        </div>
        {group.type==="mid"&&(
          <div style={{fontSize:9,color:C.muted,marginTop:2}}>{group.date}</div>
        )}
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   CYCLE CARD
═══════════════════════════════════════════════════════════ */
const CycleCard = ({cycle,cycleNum,isActive}) => {
  const [expanded,setExpanded] = useState(isActive);
  const duration = fmtDuration(cycle.opened, cycle.closed);
  const netPnl   = cycle.pnl ?? (cycle.interestEarned - cycle.interestPaid - cycle.gasUsd);
  const pnlColor = netPnl >= 0 ? C.green : C.red;
  const isRunning = cycle.status === "active";

  return (
    <div style={{
      border:`1px solid ${isRunning?C.cyanMid:cycle.pnl>=0?C.greenMid:C.border}`,
      borderRadius:8,overflow:"hidden",
      background:isRunning?C.cyanDim+"50":C.s2,
    }}>
      {/* Cycle header — clickable */}
      <button onClick={()=>setExpanded(e=>!e)} style={{
        width:"100%",display:"flex",alignItems:"center",gap:12,
        padding:"10px 12px",textAlign:"left",transition:"background .1s",
      }}
      onMouseOver={e=>e.currentTarget.style.background=C.hov}
      onMouseOut={e=>e.currentTarget.style.background="transparent"}>
        {/* Cycle number */}
        <div style={{
          width:28,height:28,borderRadius:6,flexShrink:0,
          background:isRunning?C.cyanDim:C.s3,
          border:`1px solid ${isRunning?C.cyanMid:C.border}`,
          display:"flex",alignItems:"center",justifyContent:"center",
          fontFamily:"'IBM Plex Mono',monospace",fontSize:11,fontWeight:700,
          color:isRunning?C.cyan:C.sub,
        }}>
          #{cycleNum}
        </div>

        {/* Dates + summary */}
        <div style={{flex:1,minWidth:0}}>
          <div style={{display:"flex",alignItems:"center",gap:7,marginBottom:3}}>
            <span className="mono" style={{fontSize:10,color:C.sub}}>{cycle.opened}</span>
            <span style={{fontSize:10,color:C.muted}}>→</span>
            <span className="mono" style={{fontSize:10,color:C.sub}}>
              {cycle.closed||<span style={{color:C.cyan}}>present</span>}
            </span>
            <span style={{fontSize:9,color:C.muted}}>({duration})</span>
            {isRunning
              ?<Tag v="cyan">Active</Tag>
              :<Tag v="def">Closed</Tag>
            }
          </div>
          <div style={{fontSize:10,color:C.muted,overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>
            {cycle.summary}
          </div>
        </div>

        {/* P&L summary */}
        <div style={{display:"flex",gap:14,alignItems:"center",flexShrink:0}}>
          {/* Interest breakdown */}
          <div style={{textAlign:"right"}}>
            <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:2}}>
              {isRunning?"Accrued":"Interest"}
            </div>
            <div style={{display:"flex",gap:8,alignItems:"baseline"}}>
              <span className="mono" style={{fontSize:10,color:C.green}}>+{fmt$(cycle.interestEarned)}</span>
              <span className="mono" style={{fontSize:10,color:C.red}}>-{fmt$(cycle.interestPaid)}</span>
            </div>
          </div>
          {/* Net P&L */}
          <div style={{
            padding:"5px 10px",borderRadius:6,
            background:netPnl>=0?C.greenDim:C.redDim,
            border:`1px solid ${netPnl>=0?C.greenMid:C.redMid}`,
            textAlign:"right",minWidth:72,
          }}>
            <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:2}}>
              {isRunning?"Running P&L":"Net P&L"}
            </div>
            <div className="mono" style={{fontSize:13,fontWeight:800,color:pnlColor}}>
              {netPnl>=0?"+":""}{fmt$(netPnl)}
            </div>
          </div>
        </div>

        <div style={{transform:expanded?"rotate(180deg)":"none",transition:"transform .15s",color:C.sub,flexShrink:0}}>
          <Icon n="chev_d" s={12}/>
        </div>
      </button>

      {/* Expanded: transaction timeline */}
      {expanded&&(
        <div className="aFade" style={{borderTop:`1px solid ${C.border}`,padding:"12px 12px 8px 12px"}}>
          {/* Mini stats row */}
          <div style={{display:"flex",gap:8,marginBottom:12}}>
            {[
              {l:"Peak supply", v:fmt$(cycle.peakSupplyUsd), c:C.green},
              {l:"Peak borrow", v:fmt$(cycle.peakBorrowUsd), c:C.purple},
              {l:"Gas paid",    v:fmt$(cycle.gasUsd),        c:C.sub},
              {l:"Duration",   v:duration,                   c:C.sub, mono:true},
            ].map(m=>(
              <div key={m.l} style={{flex:1,padding:"6px 9px",background:C.s3,borderRadius:5,
                border:`1px solid ${C.border}`}}>
                <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",
                  letterSpacing:".08em",marginBottom:2}}>{m.l}</div>
                <div className={m.mono?"mono":""}
                  style={{fontSize:11,fontWeight:700,color:m.c}}>{m.v}</div>
              </div>
            ))}
          </div>

          {/* Transaction groups timeline */}
          <div>
            {cycle.txGroups.map((group,i)=>(
              <TxGroupRow key={i} group={group} isLast={i===cycle.txGroups.length-1}/>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   VAULT SECTION (for Fluid/Morpho)
═══════════════════════════════════════════════════════════ */
const VaultSection = ({vault}) => {
  const [expanded,setExpanded] = useState(vault.cycles.some(c=>c.status==="active"));
  const activeCycle = vault.cycles.find(c=>c.status==="active");
  const hfColor = parseFloat(vault.healthFactor)>=2?C.green:parseFloat(vault.healthFactor)>=1.2?C.amber:C.red;

  return (
    <div style={{border:`1px solid ${C.borderHi}`,borderRadius:8,overflow:"hidden",marginBottom:6}}>
      {/* Vault header */}
      <button onClick={()=>setExpanded(e=>!e)} style={{
        width:"100%",display:"flex",alignItems:"center",gap:10,
        padding:"9px 12px",textAlign:"left",transition:"background .1s",background:"transparent",
      }}
      onMouseOver={e=>e.currentTarget.style.background=C.hov}
      onMouseOut={e=>e.currentTarget.style.background="transparent"}>
        <div style={{width:28,height:28,borderRadius:6,flexShrink:0,
          background:"rgba(100,100,100,0.1)",border:`1px solid ${C.border}`,
          display:"flex",alignItems:"center",justifyContent:"center"}}>
          <Icon n="vault" s={13} c={C.sub}/>
        </div>
        <div style={{flex:1,minWidth:0}}>
          <div style={{display:"flex",alignItems:"center",gap:7,marginBottom:2}}>
            <span style={{fontWeight:700,fontSize:12}}>{vault.name}</span>
            <Tag v="def">Vault</Tag>
            {activeCycle&&<Tag v="cyan">Active</Tag>}
          </div>
          <div style={{display:"flex",gap:10,fontSize:10,color:C.sub}}>
            <span>Supply <span className="mono" style={{color:C.green}}>{fmt$(vault.supplyUsd)}</span></span>
            <span>Borrow <span className="mono" style={{color:C.purple}}>{fmt$(vault.borrowUsd)}</span></span>
          </div>
        </div>
        {/* Health compact */}
        {activeCycle&&(
          <div style={{display:"flex",alignItems:"center",gap:7,flexShrink:0}}>
            <span style={{fontSize:9,color:C.sub}}>Health</span>
            <span className="mono" style={{fontSize:14,fontWeight:800,color:hfColor}}>{vault.healthFactor}</span>
            <div style={{width:50,height:4,background:C.border,borderRadius:2,overflow:"hidden"}}>
              <div style={{width:`${Math.min(parseFloat(vault.healthFactor)/3*100,100)}%`,
                height:"100%",background:hfColor,borderRadius:2}}/>
            </div>
          </div>
        )}
        <div style={{transform:expanded?"rotate(180deg)":"none",transition:"transform .15s",color:C.sub}}>
          <Icon n="chev_d" s={12}/>
        </div>
      </button>

      {/* Cycles */}
      {expanded&&(
        <div className="aFade" style={{borderTop:`1px solid ${C.border}`,padding:"10px 12px",
          display:"flex",flexDirection:"column",gap:6}}>
          {vault.cycles.map((cycle,i)=>(
            <CycleCard key={cycle.id} cycle={cycle} cycleNum={i+1}
              isActive={cycle.status==="active"}/>
          ))}
        </div>
      )}
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   PROTOCOL CARD
═══════════════════════════════════════════════════════════ */
const ProtocolCard = ({proto}) => {
  const [expanded,setExpanded] = useState(true);
  const activeCycles  = proto.vaultModel
    ? proto.vaults.flatMap(v=>v.cycles.filter(c=>c.status==="active"))
    : proto.cycles.filter(c=>c.status==="active");
  const closedCycles  = proto.vaultModel
    ? proto.vaults.flatMap(v=>v.cycles.filter(c=>c.status==="closed"))
    : proto.cycles.filter(c=>c.status==="closed");
  const totalCycles   = activeCycles.length + closedCycles.length;

  // Total P&L from closed cycles
  const closedPnl = closedCycles.reduce((s,c)=>s+(c.pnl||0),0);
  // Running interest from active cycles
  const activeInterest = activeCycles.reduce((s,c)=>s+(c.interestEarned-c.interestPaid-c.gasUsd),0);

  return (
    <div style={{border:`1px solid ${C.borderHi}`,borderRadius:10,overflow:"hidden",background:C.s1}}>
      {/* Protocol header */}
      <button onClick={()=>setExpanded(e=>!e)} style={{
        width:"100%",display:"flex",alignItems:"center",gap:12,
        padding:"12px 14px",textAlign:"left",transition:"background .1s",background:"transparent",
      }}
      onMouseOver={e=>e.currentTarget.style.background=C.hov}
      onMouseOut={e=>e.currentTarget.style.background="transparent"}>

        {/* Protocol logo */}
        <div style={{width:38,height:38,borderRadius:9,flexShrink:0,
          background:"rgba(59,130,246,0.12)",border:"1px solid rgba(59,130,246,0.25)",
          display:"flex",alignItems:"center",justifyContent:"center",
          fontSize:13,fontWeight:800,color:C.blue,fontFamily:"'IBM Plex Mono',monospace"}}>
          {proto.protocol[0]}
        </div>

        <div style={{flex:1,minWidth:0}}>
          <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:3}}>
            <span style={{fontWeight:800,fontSize:14}}>{proto.protocol}</span>
            <span style={{fontSize:13,color:proto.netColor}}>{proto.netIcon}</span>
            <span style={{fontSize:11,color:proto.netColor,fontWeight:600}}>{proto.net}</span>
            {proto.vaultModel&&<Tag v="purple">vault-based</Tag>}
          </div>
          <div style={{fontSize:10,color:C.sub,display:"flex",gap:10}}>
            <span><span className="mono" style={{color:C.cyan}}>{activeCycles.length}</span> active cycle{activeCycles.length!==1?"s":""}</span>
            <span><span className="mono" style={{color:C.sub}}>{closedCycles.length}</span> closed</span>
          </div>
        </div>

        {/* Health (only for non-vault active protocols) */}
        {!proto.vaultModel&&proto.healthFactor&&activeCycles.length>0&&(
          <div style={{width:160,flexShrink:0}}>
            <HealthBar hf={proto.healthFactor}/>
          </div>
        )}

        {/* P&L summary */}
        <div style={{display:"flex",gap:10,flexShrink:0,alignItems:"center"}}>
          {closedPnl!==0&&(
            <div style={{textAlign:"right"}}>
              <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:1}}>Closed P&L</div>
              <div className="mono" style={{fontSize:12,fontWeight:700,color:closedPnl>=0?C.green:C.red}}>
                {closedPnl>=0?"+":""}{fmt$(closedPnl)}
              </div>
            </div>
          )}
          {activeInterest>0&&(
            <div style={{textAlign:"right"}}>
              <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:1}}>Running</div>
              <div className="mono" style={{fontSize:12,fontWeight:700,color:C.green}}>
                +{fmt$(activeInterest)}
              </div>
            </div>
          )}
          <div style={{transform:expanded?"rotate(180deg)":"none",transition:"transform .15s",color:C.sub}}>
            <Icon n="chev_d" s={13}/>
          </div>
        </div>
      </button>

      {/* Expanded content */}
      {expanded&&(
        <div className="aFade" style={{borderTop:`1px solid ${C.border}`,padding:"12px 14px",
          display:"flex",flexDirection:"column",gap:6}}>

          {/* Non-vault: cycles directly */}
          {!proto.vaultModel&&(
            <>
              {proto.cycles.filter(c=>c.status==="active").map((cycle,i)=>(
                <CycleCard key={cycle.id} cycle={cycle} cycleNum={proto.cycles.indexOf(cycle)+1} isActive/>
              ))}
              {proto.cycles.filter(c=>c.status==="closed").map((cycle,i)=>(
                <CycleCard key={cycle.id} cycle={cycle} cycleNum={proto.cycles.indexOf(cycle)+1} isActive={false}/>
              ))}
            </>
          )}

          {/* Vault-based: vault sections */}
          {proto.vaultModel&&proto.vaults.map(vault=>(
            <VaultSection key={vault.id} vault={vault}/>
          ))}
        </div>
      )}
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   LENDING PAGE
═══════════════════════════════════════════════════════════ */
const LendingPage = () => {
  const [filter,setFilter] = useState("active"); // active | all

  // Total stats
  const allActive = PROTOCOLS.flatMap(p=>
    p.vaultModel
      ? p.vaults.flatMap(v=>v.cycles.filter(c=>c.status==="active"))
      : p.cycles.filter(c=>c.status==="active")
  );
  const allClosed = PROTOCOLS.flatMap(p=>
    p.vaultModel
      ? p.vaults.flatMap(v=>v.cycles.filter(c=>c.status==="closed"))
      : p.cycles.filter(c=>c.status==="closed")
  );
  const totalSupply = PROTOCOLS.reduce((s,p)=>
    s+(p.vaultModel?p.vaults.reduce((vs,v)=>vs+v.supplyUsd,0):(p.supplyUsd||0)),0);
  const totalBorrow = PROTOCOLS.reduce((s,p)=>
    s+(p.vaultModel?p.vaults.reduce((vb,v)=>vb+v.borrowUsd,0):(p.borrowUsd||0)),0);
  const closedPnlTotal = allClosed.reduce((s,c)=>s+(c.pnl||0),0);

  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%",overflow:"hidden"}}>
      {/* Top stats bar */}
      <div style={{flexShrink:0,padding:"10px 16px",borderBottom:`1px solid ${C.border}`,
        background:C.s1,display:"flex",gap:24,alignItems:"center"}}>
        <div>
          <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:2}}>Total supplied</div>
          <div className="mono" style={{fontSize:15,fontWeight:800,color:C.green}}>{fmt$(totalSupply)}</div>
        </div>
        <div>
          <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:2}}>Total borrowed</div>
          <div className="mono" style={{fontSize:15,fontWeight:800,color:C.purple}}>{fmt$(totalBorrow)}</div>
        </div>
        <div>
          <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:2}}>Closed P&L</div>
          <div className="mono" style={{fontSize:15,fontWeight:800,color:closedPnlTotal>=0?C.green:C.red}}>
            {closedPnlTotal>=0?"+":""}{fmt$(closedPnlTotal)}
          </div>
        </div>
        <div style={{width:1,height:28,background:C.border}}/>
        <div style={{display:"flex",gap:5}}>
          {[{id:"active",label:`Active (${allActive.length} cycles)`},{id:"all",label:"All cycles"}].map(f=>(
            <button key={f.id} onClick={()=>setFilter(f.id)} style={{
              padding:"4px 12px",borderRadius:20,fontSize:11,fontWeight:600,
              background:filter===f.id?C.cyanDim:"transparent",
              border:`1px solid ${filter===f.id?C.cyanMid:C.border}`,
              color:filter===f.id?C.cyan:C.sub,transition:"all .1s"}}>
              {f.label}
            </button>
          ))}
        </div>
        {/* Protocol legend */}
        <div style={{marginLeft:"auto",display:"flex",gap:8,alignItems:"center",fontSize:9,color:C.sub}}>
          <div style={{display:"flex",alignItems:"center",gap:4}}>
            <Tag v="def">vault-based</Tag>
            <span>= Fluid/Morpho (separate health per vault)</span>
          </div>
        </div>
      </div>

      {/* Protocol list */}
      <div style={{flex:1,overflowY:"auto",padding:"14px 16px",display:"flex",flexDirection:"column",gap:10}}>
        {PROTOCOLS.map(proto=>(
          <ProtocolCard key={proto.id} proto={proto}/>
        ))}
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   MAIN SHELL
═══════════════════════════════════════════════════════════ */
const NAV = [
  {id:"tokens",  icon:"tokens",  label:"Tokens",  color:C.cyan},
  {id:"lp",      icon:"lp",      label:"LP",       color:C.blue},
  {id:"lending", icon:"lending", label:"Lending",  color:C.green},
  {id:"staking", icon:"staking", label:"Staking",  color:C.amber, soon:true},
];

export default function App() {
  const [page,setPage] = useState("lending");
  return (
    <div style={{height:"100vh",display:"flex",flexDirection:"column",background:C.bg,overflow:"hidden"}}>
      <style>{G}</style>
      {/* TOP BAR */}
      <div style={{flexShrink:0,height:46,borderBottom:`1px solid ${C.border}`,background:C.s1,
        display:"flex",alignItems:"center",gap:12,padding:"0 14px"}}>
        <div style={{display:"flex",alignItems:"center",gap:7}}>
          <div style={{width:24,height:24,background:C.cyan,borderRadius:5,
            display:"flex",alignItems:"center",justifyContent:"center",fontSize:12}}>⚡</div>
          <span style={{fontWeight:800,fontSize:13,letterSpacing:"-.02em"}}>WalletRadar</span>
        </div>
        <div style={{width:1,height:16,background:C.border}}/>
        {[{l:"Portfolio",v:"$47.2k",c:C.text},{l:"Unrealised",v:"+7.4%",c:C.green},{l:"Realised",v:"+$1.9k",c:C.green}]
          .map(m=>(
            <div key={m.l} style={{display:"flex",gap:5,alignItems:"baseline"}}>
              <span style={{fontSize:9,color:C.sub}}>{m.l}</span>
              <span className="mono" style={{fontSize:13,fontWeight:700,color:m.c}}>{m.v}</span>
            </div>
          ))}
        <div style={{flex:1}}/>
        {[{a:"0xd8dA…6045",l:"Main",c:C.cyan},{a:"0x47ac…b503",l:"DeFi",c:C.purple}].map((w,i)=>(
          <div key={i} style={{display:"flex",alignItems:"center",gap:4,padding:"3px 8px",
            background:C.s2,border:`1px solid ${C.border}`,borderRadius:5}}>
            <div style={{width:6,height:6,borderRadius:"50%",background:w.c}}/>
            <span className="mono" style={{fontSize:9,color:C.sub}}>{w.a}·{w.l}</span>
          </div>
        ))}
      </div>
      {/* BODY */}
      <div style={{flex:1,display:"flex",overflow:"hidden"}}>
        {/* ICON NAV */}
        <div style={{width:44,flexShrink:0,borderRight:`1px solid ${C.border}`,
          display:"flex",flexDirection:"column",alignItems:"center",padding:"6px 0",gap:2}}>
          {NAV.map(s=>(
            <button key={s.id} onClick={()=>!s.soon&&setPage(s.id)}
              style={{width:36,height:36,borderRadius:7,display:"flex",alignItems:"center",justifyContent:"center",
                background:page===s.id?s.color+"20":"transparent",border:`1px solid ${page===s.id?s.color+"44":"transparent"}`,
                color:page===s.id?s.color:s.soon?C.muted:C.sub,opacity:s.soon?.35:1,
                cursor:s.soon?"not-allowed":"pointer",transition:"all .12s",position:"relative"}}>
              <Icon n={s.icon} s={15} c={page===s.id?s.color:s.soon?C.muted:C.sub}/>
              {page===s.id&&<div style={{position:"absolute",left:0,top:"20%",width:2,height:"60%",background:s.color,borderRadius:"0 2px 2px 0"}}/>}
            </button>
          ))}
          <div style={{flex:1}}/>
          <div style={{paddingBottom:4}}>
            <button style={{width:36,height:36,borderRadius:7,display:"flex",alignItems:"center",justifyContent:"center",color:C.sub}}>
              <Icon n="gear" s={15} c={C.sub}/>
            </button>
          </div>
        </div>
        {/* CONTENT */}
        <div style={{flex:1,display:"flex",flexDirection:"column",overflow:"hidden",position:"relative"}}>
          <div style={{flexShrink:0,padding:"7px 14px",borderBottom:`1px solid ${C.border}`,
            display:"flex",alignItems:"center",gap:7}}>
            <Icon n="lending" s={12} c={C.green}/>
            <span style={{fontWeight:700,fontSize:12,color:C.green}}>Lending</span>
          </div>
          {page==="lending"&&<LendingPage/>}
          {page!=="lending"&&(
            <div style={{flex:1,display:"flex",alignItems:"center",justifyContent:"center",color:C.sub,fontSize:12}}>
              Click Lending in the nav
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
