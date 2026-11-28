import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { SessionProvider } from '@odyssey/api'
import { ConfirmProvider, Splash, ThemeProvider, ToastProvider } from '@odyssey/ui'
import { App } from './App'
import { PinProvider } from './pin'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <BrowserRouter>
        <SessionProvider
          storageKey="odyssey.vendor"
          baseUrl={import.meta.env.VITE_API_URL ?? 'http://localhost:8080'}
          tenant={import.meta.env.VITE_TENANT ?? 'carwash'}
          allowedRoles={['PROVIDER_OWNER', 'PROVIDER_MANAGER', 'PROVIDER_STAFF']}
          fallback={<Splash />}
        >
          <ToastProvider><ConfirmProvider><PinProvider><App /></PinProvider></ConfirmProvider></ToastProvider>
        </SessionProvider>
      </BrowserRouter>
    </ThemeProvider>
  </StrictMode>,
)
