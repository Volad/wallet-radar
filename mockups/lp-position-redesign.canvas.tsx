import { BarChart, LineChart, useHostTheme } from "cursor/canvas";
import { useState, type CSSProperties, type ReactNode } from "react";

// ── WalletRadar design tokens ──────────────────────────────────────────────
const c = {
  bg:           "#07090f",
  surface:      "#0b0d18",
  elevated:     "#0f1220",
  hover:        "#121628",
  border:       "#181d30",
  borderStrong: "#222840",
  text:         "#dde3f0",
  subtle:       "#4a5878",
  muted:        "#252e42",
  cyan:         "#22d3ee",
  cyanSoft:     "#22d3ee14",
  cyanMid:      "#22d3ee38",
  green:        "#34d399",
  greenSoft:    "#34d39914",
  greenMid:     "#34d39938",
  red:          "#f87171",
  redSoft:      "#f8717114",
  redMid:       "#f8717138",
  amber:        "#fbbf24",
  amberSoft:    "#fbbf2414",
  amberMid:     "#fbbf2438",
  purple:       "#a78bfa",
  purpleSoft:   "#a78bfa14",
  purpleMid:    "#a78bfa38",
  blue:         "#60a5fa",
  blueSoft:     "#60a5fa14",
};
const sans = "'DM Sans','Segoe UI',sans-serif";
const mono = "'IBM Plex Mono','SFMono-Regular',Menlo,monospace";

// ── Data ───────────────────────────────────────────────────────────────────
const HIST = [
  0.01,0.02,0.05,0.11,0.22,0.38,0.57,0.75,
  0.89,0.97,1.00,0.98,0.92,0.84,0.74,0.63,
  0.52,0.42,0.33,0.25,0.19,0.14,0.10,0.07,
  0.05,0.04,0.03,0.02,0.02,0.01,0.01,0.01,
];
const EARN_CATS = [
  "28 Apr","","","","","4 May","","","","",
  "10 May","","","","","16 May","","","","",
  "22 May","","","","","","","","25","","27",
];
const EARN_DATA = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,3.8,4.21,0.15];
const APR_CATS  = ["28 Apr","4 May","10 May","16 May","22 May","27 May"];
const APR_DATA  = [0,0,0,0,280,22];

const WALLETS = [
  { id:"w1", label:"0x1a87...803f", color:"#22d3ee" },
  { id:"w2", label:"0x1e1b7...3ca9b", color:"#a78bfa" },
];
const PROTOCOLS  = ["GMX","SushiSwap","Uniswap"];
const NETWORKS   = ["Arbitrum","Base","Katana"];
const STATUSES   = ["Tracking","In range","Out of range"];
const SECTIONS   = [
  { id:"tokens",  glyph:"◈", color:"#22d3ee",  label:"Tokens" },
  { id:"lp",      glyph:"◉", color:"#34d399",  label:"Liquidity" },
  { id:"lending", glyph:"⬡", color:"#a78bfa",  label:"Lending" },
];

type Status = "In range" | "Out of range" | "Tracking";
interface LP {
  id:string; pair:string; status:Status; protocol:string; network:string;
  fee?:string; contract:string; tvl:number|null; tvlNote?:string;
  fees:number; il:number|null; netPnl:number; hasDetail:boolean;
  priceMin?:number; priceMax?:number; curPrice?:number;
  weth?:{amt:string;usd:number;pct:number}; usdc?:{amt:string;usd:number;pct:number};
  pnl?:{fees:number;il:number;price:number;net:number};
  rw?:{weth:{amt:string;usd:number};usdc:{amt:string;usd:number};total:number};
  apr?:string; nft?:string; wallet?:string; entry?:{weth:string;apr:string;tvl:string};
  txs?:Array<{type:string;date:string;legs:Array<{qty:string;sym:string;usd:string}>;total:string;n:number}>;
}
const POSITIONS: LP[] = [
  {
    id:"eth-usdc", pair:"ETH / USDC", status:"In range",
    protocol:"Uniswap", network:"Base", fee:"0.3%", contract:"0x1a87...6307",
    tvl:561.58, fees:8.15, il:-5.58, netPnl:-29.26, hasDetail:true,
    priceMin:1101, priceMax:2692, curPrice:2129,
    weth:{amt:"0.21017",usd:282.35,pct:55}, usdc:{amt:"230.28",usd:230.28,pct:45},
    pnl:{fees:8.15,il:-5.58,price:-31.83,net:-29.26},
    rw:{weth:{amt:"0.06258574",usd:4.99},usdc:{amt:"4.072899",usd:4.97},total:8.15},
    apr:"238.28%", nft:"#31481", wallet:"0x1a87...803f",
    entry:{weth:"0.157709",apr:"318.48%",tvl:"$421.86"},
    txs:[
      {type:"LP_ENTRY",date:"Jun 01, 2026",
       legs:[{qty:"0.1191",sym:"ETH",usd:"$352.80"},{qty:"248.32",sym:"USDC",usd:"$248.32"}],
       total:"$421.86",n:2},
      {type:"LP_ENTRY",date:"Jun 01, 2026",
       legs:[{qty:"0.0476",sym:"ETH",usd:"$140.87"},{qty:"70.10",sym:"USDC",usd:"$70.10"}],
       total:"$145.20",n:1},
    ],
  },
  {
    id:"eth", pair:"ETH", status:"Tracking",
    protocol:"SushiSwap", network:"Katana", contract:"0x0e87...6931",
    tvl:789.35, tvlNote:"Concentrate (NFT)", fees:0, il:0, netPnl:789.34, hasDetail:false,
    nft:"#6931", wallet:"0x1e1b7...3ca9b",
  },
  {
    id:"unknown", pair:"Unknown pair", status:"Tracking",
    protocol:"GMX", network:"Arbitrum", contract:"0x1a87...6037",
    tvl:null, tvlNote:"GMX LP", fees:0, il:null, netPnl:0, hasDetail:false,
    wallet:"0x1a87...803f",
  },
];

// ── Helpers ────────────────────────────────────────────────────────────────
const $  = (n:number,sign=false) => `${sign&&n>0?"+":""}$${Math.abs(n).toFixed(2)}`;
const pPos = (v:number,mn:number,mx:number) => Math.min(100,Math.max(0,((v-mn)/(mx-mn))*100));
const statusStyle = (s:Status) => ({
  "In range":     {border:c.greenMid, bg:c.greenSoft, fg:c.green},
  "Out of range": {border:c.redMid,   bg:c.redSoft,   fg:c.red},
  "Tracking":     {border:c.amberMid, bg:c.amberSoft,  fg:c.amber},
}[s]);

// ── Atoms ──────────────────────────────────────────────────────────────────
const B = ({s,children,onClick,onMouseEnter,onMouseLeave}:{
  s?:CSSProperties;children?:ReactNode;
  onClick?:()=>void;
  onMouseEnter?:()=>void;
  onMouseLeave?:()=>void;
}) => <div style={s} onClick={onClick} onMouseEnter={onMouseEnter} onMouseLeave={onMouseLeave}>{children}</div>;

const T = ({s,children}:{s?:CSSProperties;children?:ReactNode}) =>
  <span style={s}>{children}</span>;

const Muted = ({children,size}:{children:ReactNode;size?:string}) =>
  <T s={{fontFamily:sans,fontSize:size??"0.58rem",color:c.subtle}}>{children}</T>;
const Label = ({children}:{children:ReactNode}) =>
  <T s={{fontFamily:sans,fontSize:"0.5rem",fontWeight:700,color:c.muted,
         textTransform:"uppercase",letterSpacing:"0.1em"}}>{children}</T>;
const Mn = ({children,col,size}:{children:ReactNode;col?:string;size?:string}) =>
  <T s={{fontFamily:mono,fontSize:size??"0.62rem",color:col??c.text}}>{children}</T>;
const Hr = ({my}:{my?:number}) =>
  <B s={{height:1,background:c.border,margin:`${my??8}px 0`}} />;

// ── Status badge ───────────────────────────────────────────────────────────
function StatusBadge({status}:{status:Status}) {
  const ss = statusStyle(status);
  return (
    <T s={{display:"inline-flex",alignItems:"center",gap:4,padding:"2px 7px",
           borderRadius:999,fontSize:"0.52rem",fontWeight:700,fontFamily:sans,
           letterSpacing:"0.05em",border:`1px solid ${ss.border}`,
           background:ss.bg,color:ss.fg}}>
      <T s={{width:5,height:5,borderRadius:"50%",background:ss.fg,flexShrink:0}} />
      {status.toUpperCase()}
    </T>
  );
}

// ── Pill ───────────────────────────────────────────────────────────────────
function Pill({children,color}:{children:ReactNode;color?:"cyan"|"green"|"def"}) {
  const map:{[k:string]:{b:string;bg:string;fg:string}} = {
    cyan: {b:c.cyanMid,  bg:c.cyanSoft,  fg:c.cyan},
    green:{b:c.greenMid, bg:c.greenSoft, fg:c.green},
    def:  {b:c.border,   bg:c.elevated,  fg:c.subtle},
  };
  const s = map[color??"def"];
  return (
    <T s={{display:"inline-flex",alignItems:"center",border:`1px solid ${s.b}`,
           background:s.bg,borderRadius:"0.25rem",padding:"0.08rem 0.38rem",
           fontFamily:mono,fontSize:"0.48rem",fontWeight:700,letterSpacing:"0.04em",
           color:s.fg}}>{children}</T>
  );
}

// ── Filter checkbox chip ───────────────────────────────────────────────────
function FilterChip({label,active,color,onClick}:{
  label:string;active:boolean;color:string;onClick:()=>void;
}) {
  return (
    <button onClick={onClick} style={{
      width:"100%",border:0,borderRadius:0,background:"transparent",
      color:active?c.text:c.subtle,fontSize:"10px",lineHeight:"1.2",
      padding:"4px 0.75rem",display:"flex",alignItems:"center",gap:6,
      cursor:"pointer",textAlign:"left",fontFamily:mono,
    }}>
      <B s={{width:12,height:12,borderRadius:3,flexShrink:0,
             border:`1.5px solid ${active?color:"rgba(221,227,240,0.14)"}`,
             display:"flex",alignItems:"center",justifyContent:"center",
             background:"transparent"}}>
        {active && <B s={{width:6,height:6,borderRadius:"1.5px",background:color}} />}
      </B>
      <B s={{width:8,height:8,borderRadius:"50%",background:color,flexShrink:0}} />
      <T s={{flex:1,minWidth:0}}>{label}</T>
    </button>
  );
}

// ── Topbar ─────────────────────────────────────────────────────────────────
function Topbar() {
  const metrics = [
    {label:"Portfolio", value:"$10.7k", color:c.text},
    {label:"Unrealized",value:"-23.8%", color:c.red},
    {label:"Realized",  value:"-$1.2k", color:c.red},
    {label:"Net",       value:"$16.8k", color:c.green},
  ];
  return (
    <B s={{minHeight:"2.875rem",borderBottom:`1px solid ${c.border}`,
           background:c.surface,display:"flex",alignItems:"center",
           gap:"0.85rem",padding:"0 0.875rem",flexShrink:0}}>
      {/* Logo */}
      <B s={{display:"flex",alignItems:"center",gap:"0.4rem",flexShrink:0}}>
        <B s={{width:"1.5rem",height:"1.5rem",borderRadius:"0.35rem",
               display:"grid",placeItems:"center",background:c.cyan,
               color:"#000",fontSize:"0.7rem",fontWeight:700}}>W</B>
        <T s={{fontFamily:sans,fontWeight:800,fontSize:"0.82rem",
               letterSpacing:"-0.02em",color:c.text}}>WalletRadar</T>
      </B>

      {/* Metrics */}
      <B s={{display:"flex",alignItems:"center",gap:"0.95rem",flex:1}}>
        {metrics.map(m => (
          <B key={m.label} s={{display:"inline-flex",alignItems:"baseline",gap:"0.35rem"}}>
            <T s={{fontFamily:sans,fontSize:"0.56rem",color:c.subtle}}>{m.label}</T>
            <T s={{fontFamily:sans,fontSize:"0.78rem",fontWeight:700,color:m.color}}>{m.value}</T>
          </B>
        ))}
      </B>

      {/* Wallet chips */}
      <B s={{display:"flex",alignItems:"center",gap:"0.4rem"}}>
        {WALLETS.map(w => (
          <B key={w.id} s={{display:"inline-flex",alignItems:"center",gap:"0.25rem",
                            border:`1px solid ${c.border}`,background:c.elevated,
                            borderRadius:"0.35rem",padding:"0.22rem 0.48rem"}}>
            <B s={{width:"0.35rem",height:"0.35rem",borderRadius:"50%",background:w.color}} />
            <Mn col={c.subtle} size="0.58rem">{w.label}</Mn>
          </B>
        ))}
        {/* Refresh btn */}
        <button style={{
          border:`1px solid ${c.border}`,borderRadius:"0.35rem",
          background:"transparent",color:c.text,fontFamily:sans,
          fontSize:"0.65rem",fontWeight:700,padding:"0.3rem 0.65rem",cursor:"pointer",
        }}>Refresh</button>
      </B>
    </B>
  );
}

// ── Icon nav ───────────────────────────────────────────────────────────────
function IconNav({active}:{active:string}) {
  return (
    <B s={{width:"2.75rem",borderRight:`1px solid ${c.border}`,
           display:"flex",flexDirection:"column",alignItems:"center",
           gap:"0.2rem",padding:"0.35rem 0",flexShrink:0,background:c.bg}}>
      {SECTIONS.map(sec => (
        <B key={sec.id} s={{
          width:"2.2rem",height:"2.2rem",borderRadius:"0.45rem",
          display:"grid",placeItems:"center",cursor:"pointer",
          border:`1px solid ${active===sec.id?`${sec.color}44`:"transparent"}`,
          background:active===sec.id?`${sec.color}20`:"transparent",
          color:active===sec.id?sec.color:c.subtle,
          fontSize:"0.86rem",position:"relative",
        }}>
          {sec.glyph}
          {active===sec.id && (
            <B s={{position:"absolute",left:-1,top:"20%",
                   width:2,height:"60%",borderRadius:"0 2px 2px 0",
                   background:sec.color}} />
          )}
        </B>
      ))}
    </B>
  );
}

// ── Filters sidebar ────────────────────────────────────────────────────────
function Sidebar({
  protocols, networks, statuses, hideDust,
  toggleProtocol, toggleNetwork, toggleStatus, toggleDust,
}: {
  protocols:string[]; networks:string[]; statuses:string[]; hideDust:boolean;
  toggleProtocol:(p:string)=>void; toggleNetwork:(n:string)=>void;
  toggleStatus:(s:string)=>void; toggleDust:()=>void;
}) {
  const netColors:{[k:string]:string} = {Arbitrum:"#60a5fa",Base:"#22d3ee",Katana:"#a78bfa"};
  const protoColors:{[k:string]:string} = {GMX:"#34d399",SushiSwap:"#f87171",Uniswap:"#fbbf24"};
  const statusColors:{[k:string]:string} = {Tracking:"#fbbf24","In range":"#34d399","Out of range":"#f87171"};

  return (
    <B s={{width:"11.75rem",borderRight:`1px solid ${c.border}`,
           background:c.surface,display:"flex",flexDirection:"column",
           fontFamily:mono,overflow:"auto",flexShrink:0}}>
      {/* Head */}
      <B s={{padding:"0.65rem 0.75rem 0.7rem",borderBottom:`1px solid ${c.border}`,
             position:"sticky",top:0,zIndex:2,background:c.surface}}>
        <T s={{display:"flex",alignItems:"center",justifyContent:"space-between",
               textTransform:"uppercase",letterSpacing:"0.12em",fontSize:"0.52rem",
               fontWeight:700,color:c.muted,fontFamily:mono}}>
          Filters
          <T s={{fontSize:"0.95rem",color:c.subtle}}>›</T>
        </T>
        <T s={{fontFamily:mono,display:"block",color:c.subtle,fontSize:"9px",
               letterSpacing:"0.12em",textTransform:"uppercase",marginTop:"0.4rem"}}>
          Dashboard
        </T>
        <T s={{fontFamily:mono,display:"block",color:c.subtle,fontSize:"9px",lineHeight:"1.45"}}>
          Wallets &amp; networks
        </T>
      </B>

      <B s={{padding:"0.5rem 0 0.75rem",display:"flex",flexDirection:"column",gap:"0.5rem"}}>
        {/* Protocol */}
        <B>
          <T s={{display:"block",margin:"0 0.75rem 3px",color:c.muted,
                 fontSize:"8px",letterSpacing:"0.12em",textTransform:"uppercase",
                 fontWeight:700,fontFamily:mono}}>Protocol</T>
          {PROTOCOLS.map(p => (
            <FilterChip key={p} label={p} active={protocols.includes(p)}
              color={protoColors[p]??"#22d3ee"} onClick={()=>toggleProtocol(p)} />
          ))}
        </B>
        {/* Network */}
        <B>
          <T s={{display:"block",margin:"0 0.75rem 3px",color:c.muted,
                 fontSize:"8px",letterSpacing:"0.12em",textTransform:"uppercase",
                 fontWeight:700,fontFamily:mono}}>Network</T>
          {NETWORKS.map(n => (
            <FilterChip key={n} label={n} active={networks.includes(n)}
              color={netColors[n]??"#22d3ee"} onClick={()=>toggleNetwork(n)} />
          ))}
        </B>
        {/* Status */}
        <B>
          <T s={{display:"block",margin:"0 0.75rem 3px",color:c.muted,
                 fontSize:"8px",letterSpacing:"0.12em",textTransform:"uppercase",
                 fontWeight:700,fontFamily:mono}}>Status</T>
          {STATUSES.map(s => (
            <FilterChip key={s} label={s} active={statuses.includes(s)}
              color={statusColors[s]??"#22d3ee"} onClick={()=>toggleStatus(s)} />
          ))}
        </B>
        {/* Display */}
        <B>
          <T s={{display:"block",margin:"0 0.75rem 3px",color:c.muted,
                 fontSize:"8px",letterSpacing:"0.12em",textTransform:"uppercase",
                 fontWeight:700,fontFamily:mono}}>Display</T>
          <FilterChip label="Hide dust" active={hideDust} color={c.subtle} onClick={toggleDust} />
        </B>
      </B>
    </B>
  );
}

// ── Liquidity histogram ────────────────────────────────────────────────────
function LiquidityHistogram({data,priceMin,priceMax,curPrice,wethPct,usdcPct}:{
  data:number[];priceMin:number;priceMax:number;curPrice:number;wethPct:number;usdcPct:number;
}) {
  const maxH   = Math.max(...data);
  const curPct = pPos(curPrice,priceMin,priceMax);
  const H      = 72;
  return (
    <B>
      {/* Asset split bar */}
      <B s={{display:"flex",gap:4,marginBottom:8}}>
        <B s={{flex:wethPct,height:2,borderRadius:1,background:c.cyan,opacity:0.7}} />
        <B s={{flex:usdcPct,height:2,borderRadius:1,background:c.purple,opacity:0.7}} />
      </B>
      <B s={{display:"flex",justifyContent:"space-between",marginBottom:8}}>
        <B s={{display:"flex",alignItems:"center",gap:4}}>
          <B s={{width:7,height:7,borderRadius:1,background:c.cyan,opacity:0.7}} />
          <Muted size="0.5rem">WETH {wethPct}%</Muted>
        </B>
        <B s={{display:"flex",alignItems:"center",gap:4}}>
          <B s={{width:7,height:7,borderRadius:1,background:c.purple,opacity:0.7}} />
          <Muted size="0.5rem">USDC {usdcPct}%</Muted>
        </B>
      </B>
      <Hr my={8} />
      {/* Histogram */}
      <B s={{position:"relative"}}>
        {/* Current price label */}
        <B s={{position:"absolute",left:`${curPct}%`,top:-16,
               transform:"translateX(-50%)",
               fontFamily:mono,fontSize:"0.5rem",fontWeight:700,
               color:c.cyan,whiteSpace:"nowrap"}}>
          ${curPrice.toLocaleString()}
        </B>
        {/* Vertical marker */}
        <B s={{position:"absolute",left:`${curPct}%`,top:16,bottom:0,
               width:1,background:c.cyan,zIndex:2,pointerEvents:"none"}} />
        {/* Bars */}
        <B s={{display:"flex",alignItems:"flex-end",height:H,gap:1,marginTop:20}}>
          {data.map((v,i) => {
            const bPct = (i+0.5)/data.length;
            const inFront = bPct > curPct/100;
            return (
              <B key={i} s={{flex:1,height:`${(v/maxH)*100}%`,
                             background:inFront?c.purple:c.cyan,
                             opacity:0.55,borderRadius:"1px 1px 0 0",minWidth:2}} />
            );
          })}
        </B>
        {/* Price labels */}
        <B s={{display:"flex",justifyContent:"space-between",marginTop:4}}>
          <Mn col={c.subtle} size="0.5rem">${priceMin.toLocaleString()}</Mn>
          <Mn col={c.subtle} size="0.5rem">${priceMax.toLocaleString()}</Mn>
        </B>
      </B>
    </B>
  );
}

// ── Card wrapper ───────────────────────────────────────────────────────────
function WrCard({title,children,noPad}:{title:string;children:ReactNode;noPad?:boolean}) {
  return (
    <B s={{border:`1px solid ${c.border}`,borderRadius:"0.45rem",background:c.elevated}}>
      <B s={{padding:"0.38rem 0.75rem",borderBottom:`1px solid ${c.border}`,
             fontFamily:sans,fontSize:"0.52rem",fontWeight:700,
             color:c.subtle,textTransform:"uppercase",letterSpacing:"0.08em"}}>
        {title}
      </B>
      <B s={noPad?{}:{padding:"0.6rem 0.75rem"}}>
        {children}
      </B>
    </B>
  );
}

// ── P&L line ───────────────────────────────────────────────────────────────
function PnlLine({label,sub,value,bold,bt}:{
  label:string;sub?:string;value:number;bold?:boolean;bt?:boolean;
}) {
  const pos = value >= 0;
  return (
    <B s={{display:"flex",justifyContent:"space-between",alignItems:"flex-start",
           padding:bold?"9px 0 3px":"6px 0",
           borderTop:bt?`1px solid ${c.border}`:undefined}}>
      <B>
        <T s={{fontFamily:sans,fontSize:bold?"0.65rem":"0.6rem",
               fontWeight:bold?700:400,color:c.text}}>{label}</T>
        {sub && <B s={{fontFamily:sans,fontSize:"0.5rem",color:c.subtle,marginTop:1}}>{sub}</B>}
      </B>
      <Mn col={pos?c.green:c.red} size={bold?"0.68rem":"0.6rem"}>
        {pos?"+":""}{$(value)}
      </Mn>
    </B>
  );
}

// ── Reward line ────────────────────────────────────────────────────────────
function RewardLine({asset,amt,usd}:{asset:string;amt:string;usd:number}) {
  return (
    <B s={{display:"flex",alignItems:"center",justifyContent:"space-between",padding:"5px 0"}}>
      <B s={{display:"flex",alignItems:"center",gap:7}}>
        <B s={{width:24,height:24,borderRadius:"0.28rem",background:c.surface,
               border:`1px solid ${c.border}`,display:"flex",alignItems:"center",
               justifyContent:"center",fontFamily:mono,fontSize:"0.42rem",
               fontWeight:800,color:c.subtle}}>{asset}</B>
        <B>
          <Mn size="0.6rem">{amt}</Mn>
          <B s={{fontFamily:sans,fontSize:"0.5rem",color:c.subtle}}>{asset}</B>
        </B>
      </B>
      <Mn col={c.green} size="0.6rem">+${usd.toFixed(2)}</Mn>
    </B>
  );
}

// ── Mini table ─────────────────────────────────────────────────────────────
function MiniTable({cols,widths,rows}:{
  cols:string[];widths:string[];
  rows:Array<Array<ReactNode>>;
}) {
  return (
    <B s={{overflow:"hidden"}}>
      <B s={{display:"grid",gridTemplateColumns:widths.join(" "),
             padding:"0.32rem 0.75rem",background:c.surface,
             borderBottom:`1px solid ${c.border}`}}>
        {cols.map(h => <Label key={h}>{h}</Label>)}
      </B>
      {rows.map((row,i) => (
        <B key={i} s={{display:"grid",gridTemplateColumns:widths.join(" "),
                       padding:"0.42rem 0.75rem",alignItems:"center",
                       background:i%2===0?"transparent":c.surface,
                       borderBottom:i<rows.length-1?`1px solid ${c.border}`:undefined}}>
          {row.map((cell,j) => <B key={j}>{cell}</B>)}
        </B>
      ))}
    </B>
  );
}

// ── Stat card ──────────────────────────────────────────────────────────────
function StatCard({label,value,color}:{label:string;value:string;color?:string}) {
  return (
    <B s={{border:`1px solid ${c.border}`,borderRadius:"0.42rem",
           background:c.elevated,padding:"0.5rem 0.65rem"}}>
      <Muted size="0.5rem">{label}</Muted>
      <B s={{fontFamily:sans,fontSize:"1rem",fontWeight:700,
             color:color??c.text,marginTop:2,lineHeight:1.1}}>{value}</B>
    </B>
  );
}

// ── Expanded LP detail ─────────────────────────────────────────────────────
function LpDetail({p}:{p:LP}) {
  if (!p.hasDetail || !p.pnl || !p.rw) {
    return (
      <B s={{padding:"1.2rem",textAlign:"center",
             borderTop:`1px solid ${c.border}`,background:c.surface}}>
        <Muted>Tracking only — no historical data available.</Muted>
      </B>
    );
  }
  const pnl=p.pnl, rw=p.rw;
  return (
    <B s={{borderTop:`1px solid ${c.border}`,background:c.surface,
           padding:"0.875rem",display:"flex",flexDirection:"column",gap:10}}>

      {/* 4 stat cards */}
      <B s={{display:"grid",gridTemplateColumns:"repeat(4,minmax(0,1fr))",gap:8}}>
        <StatCard label="TVL"         value={`$${p.tvl!.toFixed(2)}`} />
        <StatCard label="Net P&L"     value={$(pnl.net,true)}         color={pnl.net>=0?c.green:c.red} />
        <StatCard label="Fees earned" value={$(rw.total,true)}        color={c.green} />
        <StatCard label="Current APR" value={p.apr!} />
      </B>

      {/* Charts */}
      <B s={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:10}}>
        <WrCard title="Daily earnings · 30 days">
          <BarChart categories={EARN_CATS}
            series={[{name:"Earnings ($)",data:EARN_DATA,tone:"success"}]}
            height={110} valuePrefix="$" showValues={false} />
          <B s={{display:"flex",justifyContent:"space-between",marginTop:5}}>
            <Muted size="0.5rem">Avg per day</Muted>
            <Mn col={c.subtle} size="0.5rem">$0.27</Mn>
          </B>
        </WrCard>
        <WrCard title="Historical APR · 30 days">
          <LineChart categories={APR_CATS}
            series={[{name:"APR (%)",data:APR_DATA}]}
            height={110} valueSuffix="%" beginAtZero fill />
          <B s={{display:"flex",justifyContent:"space-between",marginTop:5}}>
            <Muted size="0.5rem">Avg ext. APR</Muted>
            <Mn col={c.subtle} size="0.5rem">22.57%</Mn>
          </B>
        </WrCard>
      </B>

      {/* P&L / Rewards / Distribution */}
      <B s={{display:"grid",gridTemplateColumns:"1fr 1fr 1fr",gap:10}}>
        <WrCard title="P&L Breakdown">
          <PnlLine label="Fees earned"        sub="accumulated"        value={pnl.fees} />
          <Hr my={3} />
          <PnlLine label="Impermanent loss"   sub="vs. hold"           value={pnl.il} />
          <Hr my={3} />
          <PnlLine label="Price appreciation" sub="underlying change"  value={pnl.price} />
          <PnlLine label="Net P&L"            value={pnl.net}          bold bt />
        </WrCard>

        <WrCard title="Fees & Rewards">
          <RewardLine asset="WETH" amt={rw.weth.amt} usd={rw.weth.usd} />
          <Hr my={3} />
          <RewardLine asset="USDC" amt={rw.usdc.amt} usd={rw.usdc.usd} />
          <Hr my={8} />
          <B s={{display:"flex",justifyContent:"space-between",alignItems:"center"}}>
            <T s={{fontFamily:sans,fontSize:"0.6rem",color:c.subtle,fontWeight:700}}>Total</T>
            <Mn col={c.green} size="0.7rem">+${rw.total.toFixed(2)}</Mn>
          </B>
        </WrCard>

        <WrCard title="Liquidity Distribution">
          <LiquidityHistogram
            data={HIST} priceMin={p.priceMin!} priceMax={p.priceMax!}
            curPrice={p.curPrice!} wethPct={p.weth!.pct} usdcPct={p.usdc!.pct} />
        </WrCard>
      </B>

      {/* Entry vs Current */}
      <WrCard title="Entry vs Current" noPad>
        <MiniTable
          cols={["Metric","At entry","Current","Change"]}
          widths={["36%","21%","22%","21%"]}
          rows={[
            [<Muted size="0.6rem">WETH amount</Muted>,
             <Mn col={c.subtle} size="0.6rem">{p.entry!.weth}</Mn>,
             <Mn size="0.6rem">{p.weth!.amt}</Mn>,
             <Mn col={c.green} size="0.6rem">+0.05246</Mn>],
            [<Muted size="0.6rem">APR</Muted>,
             <Mn col={c.subtle} size="0.6rem">{p.entry!.apr}</Mn>,
             <Mn size="0.6rem">{p.apr}</Mn>,
             <Mn col={c.red} size="0.6rem">−80.20 pp</Mn>],
            [<Muted size="0.6rem">TVL (USD)</Muted>,
             <Mn col={c.subtle} size="0.6rem">{p.entry!.tvl}</Mn>,
             <Mn size="0.6rem">${p.tvl!.toFixed(2)}</Mn>,
             <Mn col={c.green} size="0.6rem">+$139.72</Mn>],
          ]}
        />
      </WrCard>

      {/* Transaction history */}
      <WrCard title="Transaction History" noPad>
        <MiniTable
          cols={["Type","Date","Amount","Total","Txs"]}
          widths={["20%","18%","36%","16%","10%"]}
          rows={(p.txs??[]).map(tx => [
            <Pill>{tx.type}</Pill>,
            <Mn col={c.subtle} size="0.56rem">{tx.date}</Mn>,
            <B>
              {tx.legs.map((leg,li) => (
                <B key={li} s={{display:"flex",alignItems:"baseline",gap:4}}>
                  <Mn col={c.red} size="0.6rem">{leg.qty} {leg.sym}</Mn>
                  <Mn col={c.subtle} size="0.52rem">/ {leg.usd}</Mn>
                </B>
              ))}
            </B>,
            <Mn size="0.62rem">{tx.total}</Mn>,
            <B s={{display:"flex",alignItems:"center",justifyContent:"center",
                   width:18,height:18,border:`1px solid ${c.border}`,borderRadius:"0.22rem",
                   background:c.surface,fontFamily:mono,fontSize:"0.52rem",color:c.subtle}}>
              {tx.n}
            </B>,
          ])}
        />
      </WrCard>
    </B>
  );
}

// ── LP row ─────────────────────────────────────────────────────────────────
// Left (wide): pair name + status on line 1 · protocol / network / wallet / nft on line 2
// Right (4 fixed cols): TVL | Fees | P&L | APR
const ROW_COLS = "minmax(0,1fr) 6rem 6.5rem 7rem 5rem";

const NET_COLORS: Record<string,string> = {Arbitrum:c.blue,Base:c.cyan,Katana:c.purple,default:c.subtle};

function LpRow({p,open,onToggle}:{p:LP;open:boolean;onToggle:()=>void}) {
  const [hov, setHov] = useState(false);
  const netCol   = p.netPnl>0 ? c.green : p.netPnl<0 ? c.red : c.subtle;
  const netColor = NET_COLORS[p.network] ?? NET_COLORS.default;

  return (
    <B s={{borderBottom:`1px solid ${c.border}`}}>
      <B
        onClick={onToggle}
        onMouseEnter={()=>setHov(true)}
        onMouseLeave={()=>setHov(false)}
        s={{display:"grid",gridTemplateColumns:ROW_COLS,
            alignItems:"center",gap:8,padding:"0.48rem 0.875rem",cursor:"pointer",
            background:open||hov?c.hover:"transparent",
            borderLeft:`2px solid ${open?c.cyanMid:"transparent"}`,
            transition:"background 0.1s ease"}}>

        {/* ── Left: identity block ── */}
        <B>
          {/* Line 1: pair + status + fee */}
          <B s={{display:"flex",alignItems:"center",gap:6,marginBottom:4}}>
            <T s={{fontFamily:sans,fontSize:"0.75rem",fontWeight:700,color:c.text}}>
              {p.pair}
            </T>
            <StatusBadge status={p.status} />
            {p.fee && (
              <T s={{fontFamily:mono,fontSize:"0.48rem",color:c.muted,
                     border:`1px solid ${c.border}`,borderRadius:"0.2rem",
                     padding:"0.06rem 0.3rem"}}>
                {p.fee}
              </T>
            )}
          </B>

          {/* Line 2: protocol · network chip · wallet dot · nft */}
          <B s={{display:"flex",alignItems:"center",gap:5,flexWrap:"wrap" as const}}>
            {/* Protocol */}
            <Muted size="0.56rem">{p.protocol}</Muted>

            <Muted size="0.56rem">·</Muted>

            {/* Network with colored dot */}
            <B s={{display:"flex",alignItems:"center",gap:3}}>
              <B s={{width:5,height:5,borderRadius:"50%",background:netColor,flexShrink:0}} />
              <Muted size="0.56rem">{p.network}</Muted>
            </B>

            <Muted size="0.56rem">·</Muted>

            {/* Wallet */}
            <B s={{display:"flex",alignItems:"center",gap:3}}>
              <B s={{width:5,height:5,borderRadius:"50%",background:c.cyan,flexShrink:0}} />
              <Mn col={c.subtle} size="0.52rem">{p.wallet ?? p.contract}</Mn>
            </B>

            {/* NFT id */}
            {p.nft && (
              <>
                <Muted size="0.56rem">·</Muted>
                <T s={{fontFamily:mono,fontSize:"0.48rem",color:c.purple,
                       border:`1px solid ${c.purpleMid}`,background:c.purpleSoft,
                       borderRadius:"0.2rem",padding:"0.06rem 0.3rem"}}>
                  {p.nft}
                </T>
              </>
            )}
          </B>
        </B>

        {/* ── TVL ── */}
        <B>
          {p.tvlNote && <Muted size="0.48rem">{p.tvlNote}</Muted>}
          <Mn size="0.68rem" col={p.tvl!==null?c.text:c.subtle}>
            {p.tvl!==null?`$${p.tvl.toFixed(2)}`:"—"}
          </Mn>
        </B>

        {/* ── Fees / IL stacked ── */}
        <B>
          <Mn col={p.fees>0?c.green:c.subtle} size="0.65rem">{$(p.fees,true)}</Mn>
          <B s={{marginTop:2}}>
            <Mn col={p.il!==null && p.il<0 ? c.red : c.subtle} size="0.55rem">
              {p.il!==null ? $(p.il) : "N/A"}
            </Mn>
            <Muted size="0.48rem"> IL</Muted>
          </B>
        </B>

        {/* ── Net P&L ── */}
        <Mn col={netCol} size="0.68rem">
          {p.netPnl>0?"+":""}{$(p.netPnl)}
        </Mn>

        {/* ── APR + chevron ── */}
        <B s={{display:"flex",alignItems:"center",justifyContent:"space-between"}}>
          <Mn col={p.apr?c.text:c.subtle} size="0.65rem">{p.apr??"-"}</Mn>
          <T s={{fontFamily:mono,fontSize:"0.55rem",color:c.subtle,
                 display:"inline-block",marginLeft:6,
                 transform:open?"rotate(180deg)":"rotate(0deg)",
                 transition:"transform 0.15s ease"}}>▾</T>
        </B>
      </B>
      {open && <LpDetail p={p} />}
    </B>
  );
}

// ── LP main content ────────────────────────────────────────────────────────
function LpContent({positions}:{positions:LP[]}) {
  const [tab, setTab]           = useState<"active"|"closed"|"all">("active");
  const [expandedId, setExpandedId] = useState<string|null>(null);

  const toggle = (id:string) => setExpandedId(prev=>prev===id?null:id);

  const filtered = positions.filter(p=>
    tab==="all" ? true :
    tab==="active" ? p.status!=="Tracking" :
    p.status==="Tracking"
  );

  const totalFees    = positions.reduce((s,p)=>s+p.fees,0);
  const totalTvl     = positions.reduce((s,p)=>s+(p.tvl??0),0);
  const inRangeCount = positions.filter(p=>p.status==="In range").length;
  const outOfRange   = positions.filter(p=>p.status==="Out of range").length;

  const tabBtn = (label:string, id:"active"|"closed"|"all") => (
    <button onClick={()=>setTab(id)} style={{
      border:`1px solid ${tab===id?c.cyanMid:c.border}`,borderRadius:999,
      background:tab===id?c.cyanSoft:"transparent",
      color:tab===id?c.cyan:c.subtle,
      fontFamily:sans,fontSize:"0.58rem",fontWeight:600,
      padding:"0.18rem 0.55rem",cursor:"pointer",
    }}>{label}</button>
  );

  return (
    <B s={{flex:1,minWidth:0,display:"flex",flexDirection:"column",overflow:"hidden"}}>
      {/* Section header */}
      <B s={{minHeight:"1.85rem",borderBottom:`1px solid ${c.border}`,
             display:"flex",alignItems:"center",gap:"0.45rem",
             padding:"0 0.75rem",background:c.bg,flexShrink:0}}>
        <T s={{color:c.green,fontSize:"0.68rem"}}>◉</T>
        <T s={{fontFamily:mono,fontSize:"0.58rem",fontWeight:700,
               letterSpacing:"0.12em",textTransform:"uppercase",color:c.green}}>
          Liquidity Pools
        </T>
      </B>

      {/* Toolbar */}
      <B s={{minHeight:"2.1rem",borderBottom:`1px solid ${c.border}`,
             display:"flex",alignItems:"center",gap:"0.35rem",
             padding:"0 0.75rem",flexShrink:0}}>
        {tabBtn("Active","active")}
        {tabBtn("Closed","closed")}
        {tabBtn("All","all")}
        <B s={{marginLeft:"auto",display:"flex",alignItems:"center",gap:"0.2rem"}}>
          <Muted size="0.6rem">Total fees:</Muted>
          <Mn col={c.green} size="0.6rem">&nbsp;${totalFees.toFixed(2)}</Mn>
        </B>
      </B>

      {/* Summary strip */}
      <B s={{display:"flex",alignItems:"center",gap:"1.2rem",
             padding:"0.42rem 0.875rem",borderBottom:`1px solid ${c.border}`,
             background:c.surface,flexShrink:0,flexWrap:"wrap" as const}}>
        {([
          ["Active TVL",   `$${(totalTvl/1000).toFixed(1)}k`, undefined],
          ["Fees Earned",  $(totalFees,true),                  c.green],
          ["Unclaimed",    $(totalFees,true),                  c.cyan],
          ["In Range",     String(inRangeCount),               c.green],
          ["Out of Range", String(outOfRange),                 outOfRange>0?c.red:c.subtle],
          ["Realized P&L", "$0.00",                            undefined],
        ] as [string,string,string|undefined][]).map(([label,val,col]) => (
          <B key={label} s={{display:"inline-flex",alignItems:"baseline",gap:"0.32rem"}}>
            <T s={{fontFamily:sans,fontSize:"0.5rem",color:c.subtle,
                   textTransform:"uppercase",letterSpacing:"0.08em"}}>{label}</T>
            <T s={{fontFamily:sans,fontSize:"0.78rem",fontWeight:700,color:col??c.text}}>{val}</T>
          </B>
        ))}
      </B>

      {/* Column headers */}
      <B s={{display:"grid",gridTemplateColumns:ROW_COLS,
             gap:8,padding:"0.26rem 0.875rem",borderBottom:`1px solid ${c.border}`,
             flexShrink:0}}>
        <Label>Position</Label>
        <Label>TVL</Label>
        <B s={{display:"flex",alignItems:"center",gap:3}}>
          <Label>Fees</Label>
          <T s={{fontFamily:sans,fontSize:"0.44rem",fontWeight:700,
                 color:c.muted,letterSpacing:"0.1em"}}> / IL</T>
        </B>
        <Label>Net P&amp;L</Label>
        <Label>APR</Label>
      </B>

      {/* LP list (scrollable) */}
      <B s={{flex:1,overflow:"auto",minHeight:0}}>
        {filtered.length===0 ? (
          <B s={{padding:"2rem",textAlign:"center",fontFamily:sans,
                 fontSize:"0.7rem",color:c.subtle}}>
            No positions match the current filters.
          </B>
        ) : (
          filtered.map(p => (
            <LpRow key={p.id} p={p} open={expandedId===p.id}
              onToggle={()=>toggle(p.id)} />
          ))
        )}
      </B>
    </B>
  );
}

// ── Main ───────────────────────────────────────────────────────────────────
export default function LiquidityPoolsPage() {
  useHostTheme();

  const [selProtos,  setSelProtos]  = useState<string[]>(PROTOCOLS);
  const [selNets,    setSelNets]    = useState<string[]>(NETWORKS);
  const [selStatuses,setSelStatuses]= useState<string[]>(STATUSES);
  const [hideDust,   setHideDust]   = useState(true);

  const toggle = (arr:string[], setArr:(v:string[])=>void, val:string) =>
    setArr(arr.includes(val) ? arr.filter(x=>x!==val) : [...arr,val]);

  const visible = POSITIONS.filter(p =>
    selProtos.includes(p.protocol) &&
    selNets.includes(p.network) &&
    selStatuses.includes(p.status)
  );

  return (
    <B s={{fontFamily:sans,color:c.text,background:c.bg,
           height:"100vh",display:"flex",flexDirection:"column",overflow:"hidden"}}>
      <Topbar />
      <B s={{flex:1,minHeight:0,display:"flex",overflow:"hidden"}}>
        <IconNav active="lp" />
        <Sidebar
          protocols={selProtos}   networks={selNets}
          statuses={selStatuses}  hideDust={hideDust}
          toggleProtocol={v=>toggle(selProtos,  setSelProtos,  v)}
          toggleNetwork= {v=>toggle(selNets,    setSelNets,    v)}
          toggleStatus=  {v=>toggle(selStatuses,setSelStatuses,v)}
          toggleDust={()=>setHideDust(h=>!h)}
        />
        <LpContent positions={visible} />
      </B>
    </B>
  );
}
