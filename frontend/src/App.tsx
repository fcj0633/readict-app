import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './account.css';
import {PreferencesProvider} from './features/settings/PreferencesProvider';
import StatusPage from './StatusPage';
import { AuthProvider } from './features/auth/AuthProvider';
import { CredentialPage, ForgotPage, RecoveryProvider, ResetPage } from './features/auth/Pages';
import AccountPage from './features/auth/AccountPage';
import SettingsPage from './features/settings/SettingsPage';
import BookshelfPage from './features/library/BookshelfPage';
import FavoritesPage from './features/favorites/FavoritesPage';
import ReaderPage from './features/reader/ReaderPage';
import { AppLayout, AuthLayout, RouteEffects } from './app/AppLayout';
import { RouteGuard, ReaderGuard } from './app/RouteGuard';
export default function App() { return <BrowserRouter><AuthProvider><RecoveryProvider><PreferencesProvider><RouteEffects/><Routes>
<Route path="/" element={<Navigate to="/books" replace/>}/><Route path="/status" element={<div className="status-page"><StatusPage/></div>}/>
<Route element={<AuthLayout/>}><Route path="/login" element={<CredentialPage key="login"/>}/><Route path="/register" element={<CredentialPage key="register" registration/>}/><Route path="/forgot-password" element={<ForgotPage/>}/><Route path="/reset-password" element={<ResetPage/>}/></Route>
<Route element={<RouteGuard/>}><Route element={<AppLayout/>}><Route path="/books" element={<BookshelfPage/>}/><Route path="/favorites" element={<FavoritesPage/>}/><Route path="/account" element={<AccountPage/>}/><Route path="/settings/reading" element={<SettingsPage/>}/><Route path="/settings" element={<Navigate to="/settings/reading" replace/>}/><Route path="/settings/*" element={<Navigate to="/settings/reading" replace/>}/></Route><Route path="/reader/:bookId" element={<ReaderGuard><ReaderPage/></ReaderGuard>}/></Route><Route path="*" element={<Navigate to="/books" replace/>}/>
</Routes></PreferencesProvider></RecoveryProvider></AuthProvider></BrowserRouter>; }
