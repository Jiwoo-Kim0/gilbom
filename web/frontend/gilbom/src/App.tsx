import { RouterProvider } from 'react-router/dom'
import { router } from './app/router'
import './App.css'

function App() {
  // Router 인스턴스는 React tree 밖에서 한 번만 만들어 navigation 상태를 보존한다.
  return <RouterProvider router={router} />
}

export default App
