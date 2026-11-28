import type {User} from './api';
export type ContactView={kind:'unbound'}|{kind:'bound';value:string;verified:boolean};
export type AccountPresentation={displayName:string;email:ContactView;phone:ContactView;avatarUrl?:string};
export type ProfileCapabilities={editNickname:boolean;uploadAvatar:boolean;bindContact:boolean;changeContact:boolean};
export type ProfileExtension={nickname?:string;avatarUrl?:string};
export interface ProfileEditorPort{updateProfile(input:{nickname?:string},signal?:AbortSignal):Promise<ProfileExtension>}
export const profileCapabilities:Readonly<ProfileCapabilities>={editNickname:false,uploadAvatar:false,bindContact:false,changeContact:false};
export function accountPresentation(user:User):AccountPresentation{return {displayName:'读者',email:user.email?{kind:'bound',value:user.email,verified:user.emailVerified}:{kind:'unbound'},phone:user.phone?{kind:'bound',value:user.phone,verified:user.phoneVerified}:{kind:'unbound'}};}

export function maskedContact(user:User):string {
 if(user.email){const at=user.email.indexOf('@');return Array.from(user.email.slice(0,at))[0]+'***'+user.email.slice(at);}
 return user.phone?.replace(/^(\+86)?(\d{3})\d{4}(\d{4})$/,'$1$2****$3')??'尚未绑定';
}
