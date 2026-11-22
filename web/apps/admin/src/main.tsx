import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { SessionProvider } from '@odyssey/api'
import { ToastProvider } from '@odyssey/ui'
import { App } from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider
        storageKey="odyssey.admin"
        baseUrl={import.meta.env.VITE_API_URL ?? 'http://localhost:8080'}
        tenant={import.meta.env.VITE_TENANT ?? 'carwash'}
        allowedRoles={['ADMIN', 'SUPPORT', 'FINANCE']}
      >
        <ToastProvider><App /></ToastProvider>
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
)
