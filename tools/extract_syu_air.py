"""Extract bounded protocol facts from the pinned public SYU reference, never execute it.
Requires javalang==0.13.0. Unsupported expressions fail closed and are reported.
"""
import argparse, collections, hashlib, json, pathlib, re
import javalang
from javalang import tree as T

class Unknown(Exception): pass
class Break(Exception): pass
class Return(Exception): pass

class Facts:
    def __init__(self, profile, constants, defaults, reverse_temperature=None):
        self.profile=profile; self.constants=constants; self.env=dict(defaults); self.frames=[]
        self.reverse_temperature=reverse_temperature
    def expr(self,n):
        if n is None:return None
        if isinstance(n,T.Literal):
            v=n.value
            if v=='true':r=True
            elif v=='false':r=False
            elif v=='null':r=None
            elif v.startswith('"'):r=json.loads(v)
            else:
                try:r=int(v.rstrip('Ll'),0) if v!='0' else 0
                except ValueError:raise Unknown('literal '+v)
        elif isinstance(n,T.MemberReference):
            q=n.qualifier or ''
            if q=='DataCanbus' and n.member=='DATA':
                if len(n.selectors or [])!=1 or self.expr(n.selectors[0].index)!=1000:raise Unknown('live-data')
                r=self.profile
            elif q=='FinalCanbus':r=self.constants[n.member]
            elif q=='ConstAllAirDara':
                if n.member not in self.env:raise Unknown('constant '+n.member)
                r=self.env[n.member]
            elif q=='R.id':r=n.member
            elif not q and n.member in self.env:r=self.env[n.member]
            else:raise Unknown('member '+q+'.'+n.member)
        elif isinstance(n,T.BinaryOperation):
            a=self.expr(n.operandl)
            if n.operator=='&&' and not a:return False
            if n.operator=='||' and a:return True
            b=self.expr(n.operandr)
            ops={'==':lambda:a==b,'!=':lambda:a!=b,'<':lambda:a<b,'>':lambda:a>b,'<=':lambda:a<=b,'>=':lambda:a>=b,'&&':lambda:a and b,'||':lambda:a or b,'+':lambda:a+b,'-':lambda:a-b,'*':lambda:a*b,'/':lambda:int(a/b),'%':lambda:a%b,'&':lambda:a&b,'|':lambda:a|b,'<<':lambda:a<<b,'>>':lambda:a>>b}
            if n.operator not in ops:raise Unknown('operator')
            return ops[n.operator]()
        elif isinstance(n,T.TernaryExpression):return self.expr(n.if_true if self.expr(n.condition) else n.if_false)
        elif isinstance(n,T.Cast):return self.expr(n.expression)
        elif isinstance(n,T.ClassReference):return n.type.name
        elif isinstance(n,T.ArrayCreator):
            if n.initializer:r=[self.expr(x) for x in n.initializer.initializers]
            else:
                count=self.expr(n.dimensions[0]);
                if not isinstance(count,int) or not 0<=count<=16:raise Unknown('array')
                r=[0]*count
        elif isinstance(n,T.Assignment):
            if n.type!='=' or not isinstance(n.expressionl,T.MemberReference):raise Unknown('assignment')
            r=self.expr(n.value); self.env[n.expressionl.member]=r
        elif isinstance(n,T.MethodInvocation):
            if n.qualifier=='SystemProperties' and n.member=='getBoolean':
                args=[self.expr(a) for a in n.arguments]
                if args==['persist.fyt.reversetemp',False] and type(self.reverse_temperature)==bool:
                    return self.reverse_temperature
                raise Unknown('unresolved firmware property')
            if n.member=='initClearAllId':return None
            if n.member in ('getId','getAction'):return self.env[n.member]
            if n.qualifier=='DataCanbus.PROXY' and n.member=='cmd':
                args=[self.expr(a) for a in n.arguments]
                if len(args)==4 and args[2] is None and args[3] is None:
                    cmd,ints=args[:2]
                elif len(args) in (2,3) and all(type(x)==int for x in args):
                    cmd,ints=args[0],args[1:]
                else:raise Unknown('cmd signature')
                if not isinstance(cmd,int) or not 0<=cmd<=255 or not isinstance(ints,list) or not 1<=len(ints)<=8 or any(type(x)!=int or not 0<=x<=255 for x in ints):raise Unknown('cmd bounds')
                self.frames.append([cmd,ints]);return None
            if n.qualifier=='RzcKlcFunc' and n.member=='CAR_AIR_CONTROL':
                vals=[self.expr(a) for a in n.arguments]
                if len(vals)!=2 or any(type(x)!=int or not 0<=x<=255 for x in vals):raise Unknown('fallback bounds')
                self.frames.append([1,vals]);return None
            raise Unknown('call '+n.qualifier+'.'+n.member)
        else:raise Unknown(type(n).__name__)
        for op in getattr(n,'prefix_operators',[]) or []:
            if op=='-':r=-r
            elif op=='!':r=not r
            elif op=='~':r=~r
            else:raise Unknown('prefix')
        if getattr(n,'postfix_operators',[]):raise Unknown('postfix')
        return r
    def run(self, nodes):
        for n in nodes or []:
            if isinstance(n,T.StatementExpression):self.expr(n.expression)
            elif isinstance(n,T.LocalVariableDeclaration):
                for d in n.declarators:self.env[d.name]=self.expr(d.initializer)
            elif isinstance(n,T.BlockStatement):self.run(n.statements)
            elif isinstance(n,T.IfStatement):
                branch=n.then_statement if self.expr(n.condition) else n.else_statement
                if branch:self.run([branch])
            elif isinstance(n,T.SwitchStatement):
                value=self.expr(n.expression);start=None;default=None
                for i,c in enumerate(n.cases):
                    if not c.case:default=i
                    elif any(self.expr(x)==value for x in c.case):start=i;break
                if start is None:start=default
                if start is not None:
                    try:
                        for c in n.cases[start:]:self.run(c.statements)
                    except Break:pass
            elif isinstance(n,T.BreakStatement):raise Break()
            elif isinstance(n,T.ReturnStatement):raise Return()
            else:raise Unknown(type(n).__name__)


def direct_button(ms, profile, constants, env, key, value):
    """Only publish a command if a UI button forwards that exact key on both phases.
    State-dependent/absolute-value handlers require a dedicated adapter instead.
    """
    method=ms.get('onTouch')
    if not method:return False
    outer=next((n for n in method.body if isinstance(n,T.SwitchStatement)),None)
    if not outer:return False
    for index,case in enumerate(outer.cases):
        if not case.case:continue
        body=case.statements
        next_index=index
        while not body and next_index+1<len(outer.cases):
            next_index+=1;body=outer.cases[next_index].statements
        if not any(ref.qualifier == 'ConstAllAirDara' and ref.member == key for node in body for _,ref in node.filter(T.MemberReference)):
            continue
        matched=True
        for phase in (0,1):
            f=Facts(profile,constants,env);f.env.update(data0=-1,sendflag=False,getAction=phase)
            try:f.run(body)
            except Break:pass
            except (Unknown,KeyError,Return):matched=False;break
            if f.env.get('data0')!=value or f.env.get('sendflag') or f.frames:
                matched=False;break
        if matched:return True
    return False


def main():
    ap=argparse.ArgumentParser();ap.add_argument('reference',type=pathlib.Path);ap.add_argument('output',type=pathlib.Path);args=ap.parse_args()
    root=args.reference
    sources={p.name:p.read_text() for p in root.glob('*.java')}
    constants={m[0]:int(m[1]) for m in re.findall(r'public static final int (\w+) = (\d+);',sources['FinalCanbus.java'])}
    defaults={m[0]:int(m[1]) for m in re.findall(r'public static int (\w+) = (-?\d+);',sources['ConstAllAirDara.java'])}
    trees={name:javalang.parse.parse(s) for name,s in sources.items() if name.startswith(('Air_','ActivityNewAir'))}
    methods={name:{m.name:m for _,m in ast.filter(T.MethodDeclaration)} for name,ast in trees.items()}
    route=methods['ActivityNewAir.java']['launchCanbus']
    # Profile identities are protocol facts. Only inspect the routing switch.
    route_switch=next(n for n in route.body if isinstance(n,T.SwitchStatement))
    profile_ids=set(constants.values())|{Facts(0,constants,{}).expr(x) for c in route_switch.cases for x in c.case}
    from syu_temperature_facts import temperature_formats
    rows=[];reasons=collections.Counter()
    screen_profiles={}
    for screen, ms in methods.items():
        init=ms.get('initCallbackId')
        if not init:continue
        switch=next((n for n in init.body if isinstance(n,T.SwitchStatement)),None)
        if not switch:continue
        ids=set()
        for case in switch.cases:
            for label in case.case:
                try:ids.add(Facts(0,constants,{}).expr(label))
                except (Unknown,KeyError):pass
        screen_profiles[screen]=ids
        profile_ids.update(ids)

    def extract(profile, screen):
        f=Facts(profile,constants,defaults)
        row={'id':profile,'name':next((k.removeprefix('CAR_') for k,v in constants.items() if v==profile),str(profile)),'screen':screen.removesuffix('.java'),'fields':{},'commands':{}}
        ms=methods.get(screen,{})
        try:
            f.run(ms['initCallbackId'].body)
            row['fields']={k:v for k,v in f.env.items() if k.startswith('U_') and type(v)==int and 0<=v<255}
            row['limits']={k:v for k,v in f.env.items() if k.startswith('TEMPERATURE_')}
            row['temperatureFormats']=temperature_formats(profile,constants,f.env,ms)
            for key,value in list(f.env.items()):
                if not key.startswith('C_') or key=='C_CONTRAL' or type(value)!=int or not 0<=value<255:continue
                # Evaluate both firmware branches. Only invariant complete plans
                # can be bundled without knowing the head unit's property value.
                variants=[]
                for reverse in (False,True):
                    frames=[]
                    for phase in (1,0):
                        command=Facts(profile,constants,f.env,reverse_temperature=reverse)
                        command.env.update(data0=value,data1=phase)
                        try:command.run(ms['sendCmd'].body)
                        except Return:pass
                        except (Unknown,KeyError) as e:reasons['command:'+str(e)]+=1;frames=[];break
                        frames.extend(command.frames)
                    variants.append(frames)
                if variants[0]!=variants[1]:
                    reasons['command:firmware-dependent plan']+=1
                    continue
                frames=variants[0]
                if frames and len(frames)<=4 and direct_button(ms,profile,constants,f.env,key,value):row['commands'][key]=frames
        except (Unknown,KeyError) as e:row['unresolved']=str(e);reasons[str(e)]+=1
        return row

    for profile in sorted(profile_ids):
        f=Facts(profile,constants,defaults)
        try:f.run([route_switch])
        except (Unknown,KeyError) as e:reasons['route:'+str(e)]+=1;continue
        screen=f.env.get('cls')
        if screen:
            rows.append(extract(profile,screen+'.java'))
            continue
        candidates=[extract(profile,name) for name,ids in screen_profiles.items() if profile in ids]
        if not candidates:continue
        row=candidates[0]
        # Multiple screen generations may share an ID. Publish only agreeing facts;
        # never choose an arbitrary conflicting protocol.
        row['sourceScreens']=[x['screen'] for x in candidates]
        for candidate in candidates[1:]:
            for category in ('fields','commands','limits','temperatureFormats'):
                row[category]={k:v for k,v in row.get(category,{}).items() if candidate.get(category,{}).get(k)==v}
        rows.append(row)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'schema':1,'revision':'755c9ae89ef255a975bcc0ee6a149e68cce2128c','sources':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in sources.items()},'profiles':rows},separators=(',',':'))+'\n')
    print('profiles',len(rows),'with fields',sum(bool(x['fields']) for x in rows),'with commands',sum(bool(x['commands']) for x in rows),'bytes',args.output.stat().st_size)
    print(reasons.most_common(10))
if __name__=='__main__':main()
