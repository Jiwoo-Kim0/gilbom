import { RouterProvider } from 'react-router/dom'
import { router } from './app/router'
import { AuthenticationProvider } from './features/auth/AuthContext'
import './App.css'

function App() {
  return (
    <AuthenticationProvider>
      <RouterProvider router={router} />
    </AuthenticationProvider>
  )
}

export default App
