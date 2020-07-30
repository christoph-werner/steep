import "./Footer.scss"
import { Book, Home, GitHub } from "react-feather"

const Footer = () => (
  <footer>
    <div className="footer-content">
      <div className="container">
        <div className="footer-row">
          <div className="logo">
            <a href="https://igd.fraunhofer.de"><img src={require("../assets/fraunhofer.svg")} className="img-fluid" /></a>
          </div>
          <div className="social-icons">
            <a className="nav-item" href="https://steep-wms.github.io/" target="_blank" rel="noopener noreferrer">
              <Home className="feather" />
            </a>
            <a className="nav-item" href="https://steep-wms.github.io/#documentation" target="_blank" rel="noopener noreferrer">
              <Book className="feather" />
            </a>
            <a className="nav-item" href="https://github.com/steep-wms/steep" target="_blank" rel="noopener noreferrer">
              <GitHub className="feather" />
            </a>
          </div>
        </div>
      </div>
    </div>
  </footer>
)

export default Footer
